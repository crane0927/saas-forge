package io.saasforge.starter.security;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.sdk.auth.ReservedContextHeaderRegistry;
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
    private final ReceiverTokenAuthenticators authenticators;
    private final ReceiverProblemDetailsWriter problems;

    HttpReceiverAuthenticationFilter(
            ReceiverRouteCatalog catalog,
            ReceiverTokenAuthenticators authenticators,
            ReceiverProblemDetailsWriter problems) {
        this.catalog = catalog;
        this.authenticators = authenticators;
        this.problems = problems;
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
            ReceiverRouteCatalog.Route route = catalog.matching(request.getMethod(), requestPath(request));
            if (route != null) {
                authenticate(request, route);
            }
            filterChain.doFilter(request, response);
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
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request, ReceiverRouteCatalog.Route route) {
        HttpRouteCatalog.CredentialRequirement requirement = route.credentialRequirement();
        if (requirement == HttpRouteCatalog.CredentialRequirement.USER_REQUIRED) {
            establish(authenticators.user(authorization(request, TokenKind.USER)));
        } else if (requirement == HttpRouteCatalog.CredentialRequirement.USER_OPTIONAL) {
            try {
                String authorization = authorization(request, TokenKind.USER);
                if (authorization != null) {
                    establish(authenticators.user(authorization));
                }
            } catch (AccessTokenInvalidException ignored) {
                // USER_OPTIONAL 仅服务于清理会话的入口；非法 Bearer 不得阻止下游清理 Cookie。
            }
        } else if (requirement == HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED) {
            establish(authenticators.service(
                    authorization(request, TokenKind.SERVICE), route.requiredScopes()));
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

    private static void establish(Object principal) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private static String requestPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
