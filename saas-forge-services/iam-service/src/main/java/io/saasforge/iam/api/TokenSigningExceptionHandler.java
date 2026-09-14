package io.saasforge.iam.api;

import io.saasforge.iam.application.signing.TokenSigningUnavailableException;
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
public class TokenSigningExceptionHandler {

    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-((?!0{32})[0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    @ExceptionHandler(TokenSigningUnavailableException.class)
    public ResponseEntity<ProblemDetailsResponse> handle(
            TokenSigningUnavailableException exception,
            HttpServletRequest request) {
        String code = TokenSigningUnavailableException.CODE;
        ProblemDetailsResponse response = new ProblemDetailsResponse(
                URI.create("urn:saasforge:problem:token-signing-unavailable"),
                "Token signing unavailable",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                code,
                exception.getMessage(),
                traceId(request));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(response);
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

    public record ProblemDetailsResponse(
            URI type,
            String title,
            int status,
            String code,
            String detail,
            String traceId) {
    }
}
