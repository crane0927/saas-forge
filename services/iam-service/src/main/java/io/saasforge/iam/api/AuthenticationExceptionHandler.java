package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.AccessContextUnavailableException;
import io.saasforge.iam.application.authentication.AccessibleMembershipLimitExceededException;
import io.saasforge.iam.application.authentication.AuthenticationFailedException;
import io.saasforge.iam.application.authentication.AuthenticationProtectionUnavailableException;
import io.saasforge.iam.application.authentication.ContextSelectionRejectedException;
import io.saasforge.iam.application.authentication.ContextSelectionSessionInvalidException;
import io.saasforge.iam.application.authentication.TenantAccessUnavailableException;
import io.saasforge.iam.application.authentication.PasswordChangeSessionInvalidException;
import io.saasforge.iam.application.authentication.PasswordCompromisedException;
import io.saasforge.iam.application.authentication.PasswordPolicyException;
import io.saasforge.iam.application.authentication.RefreshAuthorizationRejectedException;
import io.saasforge.iam.application.authentication.RefreshSessionInvalidException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthenticationExceptionHandler {
    private static final String REFRESH_COOKIE = "__Host-sf_refresh";
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<Problem> authenticationFailed(AuthenticationFailedException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, AuthenticationFailedException.CODE,
                "Authentication failed", exception.getMessage(), request);
    }

    @ExceptionHandler(AccessContextUnavailableException.class)
    ResponseEntity<Problem> accessContextUnavailable(
            AccessContextUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, AccessContextUnavailableException.CODE,
                "Access context unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationProtectionUnavailableException.class)
    ResponseEntity<Problem> authenticationProtectionUnavailable(
            AuthenticationProtectionUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, AuthenticationProtectionUnavailableException.CODE,
                "Authentication protection unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(AccessibleMembershipLimitExceededException.class)
    ResponseEntity<Problem> accessibleMembershipLimitExceeded(
            AccessibleMembershipLimitExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, AccessibleMembershipLimitExceededException.CODE,
                "Accessible membership limit exceeded", exception.getMessage(), request);
    }

    @ExceptionHandler(TenantAccessUnavailableException.class)
    ResponseEntity<Problem> tenantAccessUnavailable(
            TenantAccessUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TenantAccessUnavailableException.CODE,
                "Tenant Access unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(ContextSelectionSessionInvalidException.class)
    ResponseEntity<Problem> contextSelectionSessionInvalid(
            ContextSelectionSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, ContextSelectionSessionInvalidException.CODE,
                "Context selection session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(ContextSelectionRejectedException.class)
    ResponseEntity<Problem> contextSelectionRejected(
            ContextSelectionRejectedException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.FORBIDDEN, ContextSelectionRejectedException.CODE,
                "Context selection rejected", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordPolicyException.class)
    ResponseEntity<Problem> passwordPolicy(PasswordPolicyException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, exception.code(),
                "Password policy violation", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordCompromisedException.class)
    ResponseEntity<Problem> passwordCompromised(
            PasswordCompromisedException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, PasswordCompromisedException.CODE,
                "Password compromised", exception.getMessage(), request);
    }

    @ExceptionHandler(PasswordChangeSessionInvalidException.class)
    ResponseEntity<Problem> passwordChangeSessionInvalid(
            PasswordChangeSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, PasswordChangeSessionInvalidException.CODE,
                "Password change session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshSessionInvalidException.class)
    ResponseEntity<Problem> refreshSessionInvalid(
            RefreshSessionInvalidException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.UNAUTHORIZED, RefreshSessionInvalidException.CODE,
                "Refresh session invalid", exception.getMessage(), request);
    }

    @ExceptionHandler(RefreshAuthorizationRejectedException.class)
    ResponseEntity<Problem> refreshAuthorizationRejected(
            RefreshAuthorizationRejectedException exception, HttpServletRequest request) {
        return problemWithClearedRefreshCookie(
                HttpStatus.FORBIDDEN, RefreshAuthorizationRejectedException.CODE,
                "Access context unavailable", exception.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestCookieException.class)
    ResponseEntity<Problem> missingRefreshCookie(
            MissingRequestCookieException exception, HttpServletRequest request) throws MissingRequestCookieException {
        if (!REFRESH_COOKIE.equals(exception.getCookieName())) {
            throw exception;
        }
        if (request.getRequestURI().endsWith("/password-changes")) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.UNAUTHORIZED, PasswordChangeSessionInvalidException.CODE,
                    "Password change session invalid", "首次改密会话无效或已失效", request);
        }
        if (request.getRequestURI().endsWith("/refresh")) {
            return problemWithClearedRefreshCookie(
                    HttpStatus.UNAUTHORIZED, RefreshSessionInvalidException.CODE,
                    "Refresh session invalid", "Refresh 会话无效或已失效", request);
        }
        return problemWithClearedRefreshCookie(HttpStatus.UNAUTHORIZED, ContextSelectionSessionInvalidException.CODE,
                "Context selection session invalid", "Tenant 上下文选择会话无效或已失效", request);
    }

    private ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        Problem body = new Problem(URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId(request));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private ResponseEntity<Problem> problemWithClearedRefreshCookie(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        Problem body = new Problem(URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH_COOKIE, "")
                        .secure(true)
                        .httpOnly(true)
                        .sameSite("Strict")
                        .path("/")
                        .maxAge(0)
                        .build().toString())
                .body(body);
    }

    private static String traceId(HttpServletRequest request) {
        Matcher matcher = TRACE_PARENT.matcher(request.getHeader("traceparent") == null
                ? ""
                : request.getHeader("traceparent"));
        if (matcher.matches()) {
            return matcher.group(1);
        }
        byte[] traceId = new byte[16];
        do {
            RANDOM.nextBytes(traceId);
        } while (allZero(traceId));
        return HexFormat.of().formatHex(traceId);
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
