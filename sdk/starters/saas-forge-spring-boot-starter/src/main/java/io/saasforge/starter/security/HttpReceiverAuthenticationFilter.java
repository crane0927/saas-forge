package io.saasforge.starter.security;

import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.context.NullSecurityContextRepository;
import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.sdk.auth.ReservedContextHeaderRegistry;
import io.saasforge.sdk.tenant.TenantContextUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅依据当前服务的 Route Catalog operation 复验原始 Token 并建立请求内 Security Context。 */
final class HttpReceiverAuthenticationFilter extends OncePerRequestFilter {

    private final ReceiverRouteCatalog catalog;
    private final int maxJsonBytes;
    private final ReceiverTokenAuthenticators authenticators;
    private final BearerTokenAuthenticationFilter resourceServer;
    private final ReceiverProblemDetailsWriter problems;

    HttpReceiverAuthenticationFilter(
            ReceiverRouteCatalog catalog,
            ReceiverTokenAuthenticators authenticators,
            ReceiverProblemDetailsWriter problems) {
        this(catalog, authenticators, problems, 1024 * 1024);
    }

    HttpReceiverAuthenticationFilter(ReceiverRouteCatalog catalog, ReceiverTokenAuthenticators authenticators,
            ReceiverProblemDetailsWriter problems, int maxJsonBytes) {
        if (maxJsonBytes < 1 || maxJsonBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("用户 JSON 请求体上限必须为正且小于 Integer.MAX_VALUE");
        }
        this.maxJsonBytes = maxJsonBytes;
        this.catalog = catalog;
        this.authenticators = authenticators;
        this.problems = problems;
        this.resourceServer = new BearerTokenAuthenticationFilter(
                (AuthenticationManagerResolver<HttpServletRequest>) request ->
                        authentication -> authenticate(request, (String) authentication.getCredentials()),
                this::bearerToken);
        this.resourceServer.setSecurityContextRepository(
                new NullSecurityContextRepository());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (hasReservedContextHeader(request)) {
                problems.write(request, response, HttpStatus.BAD_REQUEST,
                        "UNTRUSTED_CONTEXT_HEADER",
                        "Platform context headers are not accepted from HTTP requests.");
                return;
            }
            var route = catalog.matching(request.getMethod(), requestPath(request));
            HttpServletRequest guarded = route != null && (route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.USER_REQUIRED
                    || route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL)
                    ? UserTenantInputGuard.check(request, maxJsonBytes) : request;
            SecurityContextHolder.clearContext();
            resourceServer.doFilter(guarded, response, (authenticatedRequest, authenticatedResponse) -> {
                if (SecurityContextHolder.getContext().getAuthentication()
                        instanceof AnonymousAuthenticationToken) {
                    SecurityContextHolder.clearContext();
                }
                filterChain.doFilter(authenticatedRequest, authenticatedResponse);
            });
        } catch (TenantInputBodyTooLargeException exception) {
            problems.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", exception.getMessage());
        } catch (UntrustedTenantInputException exception) {
            problems.write(request, response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage());
        } catch (TenantContextUnavailableException exception) {
            problems.write(request, response, HttpStatus.FORBIDDEN,
                    "ACCESS_CONTEXT_UNAVAILABLE", exception.getMessage());
        } catch (TokenRevocationStatusUnavailableException exception) {
            problems.write(request, response, HttpStatus.SERVICE_UNAVAILABLE,
                    "TOKEN_REVOCATION_STATUS_UNAVAILABLE", exception.detail());
        } catch (ServiceAccessTokenScopeInsufficientException exception) {
            ReceiverRouteCatalog.Route route = catalog.matching(request.getMethod(), requestPath(request));
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer error=\"insufficient_scope\", scope=\""
                            + String.join(" ", route.requiredScopes()) + "\"");
            problems.write(request, response, HttpStatus.FORBIDDEN,
                    "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
                    "The Service Access Token does not grant every required scope.");
        } catch (AccessTokenInvalidException exception) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                    exception.credentialsPresent() ? "Bearer error=\"invalid_token\"" : "Bearer");
            problems.write(request, response, HttpStatus.UNAUTHORIZED,
                    "ACCESS_TOKEN_INVALID", exception.detail());
        } catch (ServletException exception) {
            if (exception.getCause() instanceof TenantContextUnavailableException unavailable) {
                problems.write(request, response, HttpStatus.FORBIDDEN,
                        "ACCESS_CONTEXT_UNAVAILABLE", unavailable.getMessage());
            } else {
                throw exception;
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Authentication bearerToken(HttpServletRequest request) {
        var route = catalog.matching(request.getMethod(), requestPath(request));
        if (route == null || !List.of(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED,
                HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL,
                HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED).contains(route.credentialRequirement())) {
            return null;
        }
        TokenKind kind = route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED
                ? TokenKind.SERVICE : TokenKind.USER;
        try {
            String value = authorization(request, kind);
            if (value == null || !value.startsWith("Bearer ") || value.length() == 7) {
                throw new AccessTokenInvalidException(kind, value != null);
            }
            return new BearerTokenAuthenticationToken(
                    value.substring(7));
        } catch (AccessTokenInvalidException exception) {
            if (route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL) {
                return null;
            }
            throw exception;
        }
    }

    private Authentication authenticate(HttpServletRequest request, String token) {
        var route = catalog.matching(request.getMethod(), requestPath(request));
        try {
            Object principal = route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED
                    ? authenticators.service("Bearer " + token, route.requiredScopes())
                    : authenticators.user("Bearer " + token);
            return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        } catch (AccessTokenInvalidException exception) {
            if (route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL) {
                return new AnonymousAuthenticationToken(
                        "optional-user", "anonymous", AuthorityUtils
                                .createAuthorityList("ROLE_ANONYMOUS"));
            }
            throw exception;
        }
    }

    private static String authorization(HttpServletRequest request, TokenKind tokenKind) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (values.size() > 1) {
            throw new AccessTokenInvalidException(tokenKind, true);
        }
        return values.isEmpty() ? null : values.get(0);
    }

    private static boolean hasReservedContextHeader(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .anyMatch(ReservedContextHeaderRegistry::contains);
    }

    private static String requestPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
