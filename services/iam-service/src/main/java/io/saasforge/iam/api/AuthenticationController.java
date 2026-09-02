package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.AccessTokenLoginResult;
import io.saasforge.iam.application.authentication.BrowserSessionSlot;
import io.saasforge.iam.application.authentication.ContextSelectionLoginResult;
import io.saasforge.iam.application.authentication.ContextSelectionService;
import io.saasforge.iam.application.authentication.ClientCredentialsInvalidException;
import io.saasforge.iam.application.authentication.ClientCredentialsTokenService;
import io.saasforge.iam.application.authentication.InitialPasswordChangeLoginResult;
import io.saasforge.iam.application.authentication.InitialPasswordChangeService;
import io.saasforge.iam.application.authentication.LoginContextType;
import io.saasforge.iam.application.authentication.LoginResult;
import io.saasforge.iam.application.authentication.LogoutService;
import io.saasforge.iam.application.authentication.PasswordLoginService;
import io.saasforge.iam.application.authentication.PasswordSetupService;
import io.saasforge.iam.application.authentication.RefreshSessionService;
import io.saasforge.iam.application.authentication.TenantContextSwitchService;
import io.saasforge.iam.contract.api.AuthenticationApi;
import io.saasforge.iam.contract.model.AccessTokenResult;
import io.saasforge.iam.contract.model.AuthenticationResult;
import io.saasforge.iam.contract.model.ContextSelectionRequiredResult;
import io.saasforge.iam.contract.model.ClientCredentialsTokenResponse;
import io.saasforge.iam.contract.model.ContextSelectionRequest;
import io.saasforge.iam.contract.model.InitialPasswordChangeRequiredResult;
import io.saasforge.iam.contract.model.LoginRequest;
import io.saasforge.iam.contract.model.MembershipCandidate;
import io.saasforge.iam.contract.model.PasswordChangeRequest;
import io.saasforge.iam.contract.model.PasswordSetupRequest;
import io.saasforge.iam.contract.model.SessionSlotRequest;
import io.saasforge.iam.contract.model.TenantSwitchRequest;
import io.saasforge.iam.contract.model.TenantAuthenticationContext;
import io.saasforge.iam.contract.model.TenantBrandProfile;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
public class AuthenticationController implements AuthenticationApi {
    static final String SESSION_SLOT_ATTRIBUTE = AuthenticationController.class.getName() + ".sessionSlot";
    private static final String LEGACY_REFRESH_COOKIE = "__Host-sf_refresh";
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final PasswordLoginService loginService;
    private final ContextSelectionService contextSelectionService;
    private final InitialPasswordChangeService passwordChangeService;
    private final PasswordSetupService passwordSetupService;
    private final RefreshSessionService refreshSessionService;
    private final LogoutService logoutService;
    private final ClientCredentialsTokenService clientCredentialsTokenService;
    private final TenantContextSwitchService tenantContextSwitchService;
    private final BrowserRequestSecurity browserRequestSecurity;

    public AuthenticationController(
            PasswordLoginService loginService,
            ContextSelectionService contextSelectionService,
            InitialPasswordChangeService passwordChangeService,
            PasswordSetupService passwordSetupService,
            RefreshSessionService refreshSessionService,
            LogoutService logoutService,
            ClientCredentialsTokenService clientCredentialsTokenService,
            TenantContextSwitchService tenantContextSwitchService,
            BrowserRequestSecurity browserRequestSecurity) {
        this.loginService = loginService;
        this.contextSelectionService = contextSelectionService;
        this.passwordChangeService = passwordChangeService;
        this.passwordSetupService = passwordSetupService;
        this.refreshSessionService = refreshSessionService;
        this.logoutService = logoutService;
        this.clientCredentialsTokenService = clientCredentialsTokenService;
        this.tenantContextSwitchService = tenantContextSwitchService;
        this.browserRequestSecurity = browserRequestSecurity;
    }

    @Override
    public ResponseEntity<ClientCredentialsTokenResponse> issueClientCredentialsToken(
            String grantType, String scope) {
        BasicCredentials credentials = basicCredentials(currentRequest().getHeader(HttpHeaders.AUTHORIZATION));
        var token = clientCredentialsTokenService.issue(
                credentials.clientId(), credentials.clientSecret(), grantType, scope);
        return ResponseEntity.ok(new ClientCredentialsTokenResponse()
                .accessToken(token.value())
                .tokenType(ClientCredentialsTokenResponse.TokenTypeEnum.BEARER)
                .expiresIn(Math.toIntExact(token.expiresInSeconds()))
                .scope(token.scope()));
    }

    @Override
    public ResponseEntity<AccessTokenResult> selectAuthenticationContext(
            String csrfHeader,
            URI ignoredOrigin,
            String refreshToken,
            ContextSelectionRequest request,
            String ignoredFetchSite) {
        requireBrowserRequest(BrowserSessionSlot.TENANT, csrfHeader);
        AccessTokenLoginResult result = contextSelectionService.select(refreshToken, request.getMembershipId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(BrowserSessionSlot.TENANT, result).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .body(accessTokenBody(result));
    }

    @Override
    public ResponseEntity<AuthenticationResult> login(
            String csrfHeader,
            URI ignoredOrigin,
            LoginRequest loginRequest,
            UUID ignoredIdempotencyKey,
            String ignoredFetchSite,
            String platformRefreshToken,
            String tenantRefreshToken) {
        LoginContextType contextType = LoginContextType.valueOf(loginRequest.getContextType().getValue());
        BrowserSessionSlot requestedSlot = BrowserSessionSlot.forLogin(contextType);
        requireBrowserRequest(requestedSlot, csrfHeader);
        LoginResult result = loginService.login(
                loginRequest.getEmail(),
                loginRequest.getPassword(),
                contextType,
                selectedToken(requestedSlot, platformRefreshToken, tenantRefreshToken),
                traceId());
        BrowserSessionSlot resultSlot = result instanceof InitialPasswordChangeLoginResult
                ? BrowserSessionSlot.PLATFORM : requestedSlot;
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(resultSlot, result).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .body(responseBody(result));
    }

    @Override
    public ResponseEntity<Void> changeInitialPassword(
            String csrfHeader,
            URI ignoredOrigin,
            String refreshToken,
            PasswordChangeRequest request,
            String ignoredFetchSite) {
        requireBrowserRequest(BrowserSessionSlot.PLATFORM, csrfHeader);
        passwordChangeService.change(refreshToken, request.getNewPassword(), traceId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        clearedCookie(BrowserSessionSlot.PLATFORM.cookieName()).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .build();
    }

    @Override
    public ResponseEntity<Void> establishPassword(
            UUID idempotencyKey, String csrfHeader, PasswordSetupRequest request) {
        passwordSetupService.establishPassword(
                idempotencyKey, request.getToken(), request.getNewPassword(), traceId());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AuthenticationResult> refreshAccessToken(
            UUID idempotencyKey,
            String csrfHeader,
            URI ignoredOrigin,
            SessionSlotRequest request,
            String ignoredFetchSite,
            String platformRefreshToken,
            String tenantRefreshToken) {
        BrowserSessionSlot slot = BrowserSessionSlot.valueOf(request.getSessionSlot().getValue());
        requireBrowserRequest(slot, csrfHeader);
        LoginResult result = refreshSessionService.refresh(
                idempotencyKey,
                slot,
                selectedToken(slot, platformRefreshToken, tenantRefreshToken),
                traceId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(slot, result).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .body(responseBody(result));
    }

    @Override
    public ResponseEntity<Void> logout(
            String csrfHeader,
            URI ignoredOrigin,
            SessionSlotRequest request,
            UUID ignoredIdempotencyKey,
            String ignoredFetchSite,
            String platformRefreshToken,
            String tenantRefreshToken) {
        HttpServletRequest httpRequest = currentRequest();
        BrowserSessionSlot slot = BrowserSessionSlot.valueOf(request.getSessionSlot().getValue());
        requireBrowserRequest(slot, csrfHeader);
        logoutService.logout(
                slot,
                selectedToken(slot, platformRefreshToken, tenantRefreshToken),
                httpRequest.getHeader(HttpHeaders.AUTHORIZATION),
                traceId(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        clearedCookie(slot.cookieName()).toString(),
                        clearedCookie(LEGACY_REFRESH_COOKIE).toString())
                .build();
    }

    @Override
    public ResponseEntity<Void> switchTenantContext(
            UUID idempotencyKey,
            String csrfHeader,
            URI ignoredOrigin,
            String refreshToken,
            TenantSwitchRequest request,
            String ignoredFetchSite) {
        requireBrowserRequest(BrowserSessionSlot.TENANT, csrfHeader);
        tenantContextSwitchService.switchContext(
                idempotencyKey, refreshToken, request.getMembershipId(), traceId(currentRequest()));
        return ResponseEntity.noContent().build();
    }

    private AuthenticationResult responseBody(LoginResult result) {
        if (result instanceof AccessTokenLoginResult accessTokenResult) {
            return accessTokenBody(accessTokenResult);
        }
        if (result instanceof InitialPasswordChangeLoginResult) {
            return new InitialPasswordChangeRequiredResult().contextState("PASSWORD_CHANGE_REQUIRED");
        }
        ContextSelectionLoginResult selectionResult = (ContextSelectionLoginResult) result;
        return new ContextSelectionRequiredResult()
                .contextState("CONTEXT_SELECTION_REQUIRED")
                .memberships(selectionResult.memberships().stream()
                        .map(membership -> new MembershipCandidate()
                                .membershipId(membership.membershipId())
                                .tenantId(membership.tenantId())
                                .tenantDisplayName(membership.tenantDisplayName()))
                        .toList());
    }

    private AccessTokenResult accessTokenBody(AccessTokenLoginResult result) {
        AccessTokenResult response = new AccessTokenResult()
                .contextState("ACCESS_TOKEN_ISSUED")
                .accessToken(result.accessToken().value())
                .tokenType(AccessTokenResult.TokenTypeEnum.BEARER)
                .expiresIn(result.accessToken().expiresInSeconds());
        if (result.tenantContext() != null) {
            var current = result.tenantContext().currentMembership();
            var tenantContext = new TenantAuthenticationContext()
                    .membershipId(current.membershipId())
                    .tenantId(current.tenantId())
                    .tenantDisplayName(current.tenantDisplayName())
                    .accessibleMemberships(result.tenantContext().accessibleMemberships().stream()
                            .map(membership -> new MembershipCandidate()
                                    .membershipId(membership.membershipId())
                                    .tenantId(membership.tenantId())
                                    .tenantDisplayName(membership.tenantDisplayName()))
                            .toList());
            if (current.brandProfile() != null) {
                var brand = current.brandProfile();
                tenantContext.brandProfile(new TenantBrandProfile()
                        .displayName(brand.displayName())
                        .logoUrl(brand.logoUrl())
                        .faviconUrl(brand.faviconUrl())
                        .primaryColor(brand.primaryColor())
                        .accentColor(brand.accentColor()));
            }
            response.tenantContext(tenantContext);
        }
        return response;
    }

    private ResponseCookie refreshCookie(BrowserSessionSlot slot, LoginResult result) {
        return ResponseCookie.from(slot.cookieName(), result.refreshToken())
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofSeconds(result.refreshCookieMaxAgeSeconds()))
                .build();
    }

    private ResponseCookie clearedCookie(String cookieName) {
        return ResponseCookie.from(cookieName, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    private void requireBrowserRequest(BrowserSessionSlot slot, String csrfHeader) {
        HttpServletRequest request = currentRequest();
        request.setAttribute(SESSION_SLOT_ATTRIBUTE, slot);
        browserRequestSecurity.requireControlledMutation(request, csrfHeader, slot);
    }

    private String selectedToken(
            BrowserSessionSlot slot,
            String platformRefreshToken,
            String tenantRefreshToken) {
        return slot == BrowserSessionSlot.PLATFORM ? platformRefreshToken : tenantRefreshToken;
    }

    private String traceId() {
        return traceId(currentRequest());
    }

    private HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    private String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Matcher matcher = TRACE_PARENT.matcher(traceparent == null ? "" : traceparent);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static BasicCredentials basicCredentials(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw new ClientCredentialsInvalidException();
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("Basic 凭据格式不合法");
            }
            String rawClientId = decoded.substring(0, separator);
            UUID clientId = UUID.fromString(rawClientId);
            if (clientId.version() != 7 || !clientId.toString().equals(rawClientId)) {
                throw new IllegalArgumentException("Client ID 格式不合法");
            }
            return new BasicCredentials(clientId, decoded.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new ClientCredentialsInvalidException();
        }
    }

    private record BasicCredentials(UUID clientId, String clientSecret) {
    }
}
