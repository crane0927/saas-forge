package io.saasforge.iam.api;

import io.saasforge.iam.application.authentication.AccessContextUnavailableException;
import io.saasforge.iam.application.authentication.AuthenticationFailedException;
import io.saasforge.iam.application.authentication.AuthenticationProtectionUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthenticationExceptionHandler {
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

    private ResponseEntity<Problem> problem(
            HttpStatus status, String code, String title, String detail, HttpServletRequest request) {
        Problem body = new Problem(URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title, status.value(), code, detail, traceId(request));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
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
