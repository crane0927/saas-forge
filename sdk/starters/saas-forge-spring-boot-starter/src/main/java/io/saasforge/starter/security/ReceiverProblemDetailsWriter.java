package io.saasforge.starter.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

final class ReceiverProblemDetailsWriter {

    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");
    private final ObjectMapper objectMapper;

    ReceiverProblemDetailsWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new Problem(
                URI.create("urn:saasforge:problem:" + code.toLowerCase().replace('_', '-')),
                title(code), status.value(), code, detail, traceId(request)));
    }

    private static String title(String code) {
        return switch (code) {
            case "ACCESS_TOKEN_INVALID" -> "Access Token invalid";
            case "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> "Access Token scope insufficient";
            case "TOKEN_REVOCATION_STATUS_UNAVAILABLE" -> "Token revocation status unavailable";
            case "UNTRUSTED_CONTEXT_HEADER" -> "Untrusted context header";
            default -> throw new IllegalArgumentException("Unsupported Starter Problem Details code: " + code);
        };
    }

    private static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        Matcher matcher = TRACE_PARENT.matcher(traceparent == null ? "" : traceparent);
        return matcher.matches() && !matcher.group(1).chars().allMatch(character -> character == '0')
                ? matcher.group(1)
                : UUID.randomUUID().toString().replace("-", "");
    }

    private record Problem(URI type, String title, int status, String code, String detail, String traceId) {
    }
}
