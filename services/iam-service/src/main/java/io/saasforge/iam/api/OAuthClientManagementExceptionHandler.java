package io.saasforge.iam.api;

import io.saasforge.iam.application.client.OAuthClientManagementAuthorizationException;
import io.saasforge.iam.application.client.OAuthClientManagementException;
import io.saasforge.iam.domain.client.OAuthClientScopeGrantForbiddenException;
import io.saasforge.iam.application.authentication.TokenRevocationStatusUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OAuthClientsController.class)
public class OAuthClientManagementExceptionHandler {
    @ExceptionHandler(OAuthClientManagementAuthorizationException.class)
    ResponseEntity<Problem> authorization(
            OAuthClientManagementAuthorizationException exception, HttpServletRequest request) {
        HttpStatus status = "ACCESS_TOKEN_INVALID".equals(exception.code())
                ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        return problem(status, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(OAuthClientScopeGrantForbiddenException.class)
    ResponseEntity<Problem> forbiddenScope(
            OAuthClientScopeGrantForbiddenException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, OAuthClientScopeGrantForbiddenException.CODE,
                exception.getMessage(), request);
    }

    @ExceptionHandler(OAuthClientManagementException.class)
    ResponseEntity<Problem> management(
            OAuthClientManagementException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case "IDEMPOTENCY_KEY_INVALID" -> HttpStatus.BAD_REQUEST;
            case "OAUTH_CLIENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        ResponseEntity<Problem> response = problem(status, exception.code(), exception.getMessage(), request);
        if ("IDEMPOTENCY_REQUEST_IN_PROGRESS".equals(exception.code())) {
            return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Problem> invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(TokenRevocationStatusUnavailableException.class)
    ResponseEntity<Problem> tokenRevocationStatusUnavailable(
            TokenRevocationStatusUnavailableException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TokenRevocationStatusUnavailableException.CODE,
                exception.getMessage(), request);
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        String traceId = OAuthClientsController.traceId(request);
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        Problem body = new Problem(
                URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                "OAuth Client management failed", status.value(), code, detail, traceId);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
