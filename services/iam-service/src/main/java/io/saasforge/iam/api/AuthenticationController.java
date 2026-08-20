package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.AccessTokenLoginResult;
import io.saasforge.iam.application.authentication.ContextSelectionLoginResult;
import io.saasforge.iam.application.authentication.ContextSelectionService;
import io.saasforge.iam.application.authentication.InitialPasswordChangeLoginResult;
import io.saasforge.iam.application.authentication.InitialPasswordChangeService;
import io.saasforge.iam.application.authentication.LoginContextType;
import io.saasforge.iam.application.authentication.LoginResult;
import io.saasforge.iam.application.authentication.LogoutService;
import io.saasforge.iam.application.authentication.PasswordLoginService;
import io.saasforge.iam.application.authentication.RefreshSessionService;
import io.saasforge.iam.contract.api.AuthenticationApi;
import io.saasforge.iam.contract.model.AccessTokenResult;
import io.saasforge.iam.contract.model.AuthenticationResult;
import io.saasforge.iam.contract.model.ContextSelectionRequiredResult;
import io.saasforge.iam.contract.model.ContextSelectionRequest;
import io.saasforge.iam.contract.model.InitialPasswordChangeRequiredResult;
import io.saasforge.iam.contract.model.LoginRequest;
import io.saasforge.iam.contract.model.MembershipCandidate;
import io.saasforge.iam.contract.model.PasswordChangeRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
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
    private static final String REFRESH_COOKIE = "__Host-sf_refresh";
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private final PasswordLoginService loginService;
    private final ContextSelectionService contextSelectionService;
    private final InitialPasswordChangeService passwordChangeService;
    private final RefreshSessionService refreshSessionService;
    private final LogoutService logoutService;

    public AuthenticationController(
            PasswordLoginService loginService,
            ContextSelectionService contextSelectionService,
            InitialPasswordChangeService passwordChangeService,
            RefreshSessionService refreshSessionService,
            LogoutService logoutService) {
        this.loginService = loginService;
        this.contextSelectionService = contextSelectionService;
        this.passwordChangeService = passwordChangeService;
        this.refreshSessionService = refreshSessionService;
        this.logoutService = logoutService;
    }

    @Override
    public ResponseEntity<AccessTokenResult> selectAuthenticationContext(
            String csrfHeader, String refreshToken, ContextSelectionRequest request) {
        AccessTokenLoginResult result = contextSelectionService.select(refreshToken, request.getMembershipId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result).toString())
                .body(accessTokenBody(result));
    }

    @Override
    public ResponseEntity<AuthenticationResult> login(
            UUID idempotencyKey, String csrfHeader, LoginRequest loginRequest) {
        LoginResult result = loginService.login(
                loginRequest.getEmail(),
                loginRequest.getPassword(),
                LoginContextType.valueOf(loginRequest.getContextType().getValue()),
                traceId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result).toString())
                .body(responseBody(result));
    }

    @Override
    public ResponseEntity<Void> changeInitialPassword(
            String csrfHeader, String refreshToken, PasswordChangeRequest request) {
        passwordChangeService.change(refreshToken, request.getNewPassword(), traceId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .build();
    }

    @Override
    public ResponseEntity<AuthenticationResult> refreshAccessToken(
            UUID idempotencyKey, String csrfHeader, String refreshToken, Object request) {
        LoginResult result = refreshSessionService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result).toString())
                .body(responseBody(result));
    }

    @Override
    public ResponseEntity<Void> logout(String csrfHeader, Object request, UUID ignoredIdempotencyKey, String refreshToken) {
        HttpServletRequest httpRequest = currentRequest();
        logoutService.logout(refreshToken, httpRequest.getHeader(HttpHeaders.AUTHORIZATION), traceId(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .build();
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
        return new AccessTokenResult()
                .contextState("ACCESS_TOKEN_ISSUED")
                .accessToken(result.accessToken().value())
                .tokenType(AccessTokenResult.TokenTypeEnum.BEARER)
                .expiresIn(result.accessToken().expiresInSeconds());
    }

    private ResponseCookie refreshCookie(LoginResult result) {
        return ResponseCookie.from(REFRESH_COOKIE, result.refreshToken())
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofSeconds(result.refreshCookieMaxAgeSeconds()))
                .build();
    }

    private ResponseCookie clearedRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
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
}
