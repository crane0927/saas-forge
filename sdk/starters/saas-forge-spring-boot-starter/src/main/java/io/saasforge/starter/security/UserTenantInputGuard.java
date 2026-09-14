package io.saasforge.starter.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 用户上下文不接受外部 Tenant 输入；服务的正式操作目标不经过该检查。 */
final class UserTenantInputGuard {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NAMES = Set.of("tenant", "tenantid", "tenantcontext", "tenantcontextid",
            "currenttenant", "currenttenantid", "xtenant", "xtenantid", "xtenantcontext");

    private UserTenantInputGuard() { }

    static HttpServletRequest check(HttpServletRequest request, int maxJsonBytes) throws IOException {
        if (Collections.list(request.getParameterNames()).stream().anyMatch(UserTenantInputGuard::reserved)) {
            throw new UntrustedTenantInputException();
        }
        String contentType = request.getContentType();
        if (contentType == null || !(contentType.toLowerCase(Locale.ROOT).contains("/json")
                || contentType.toLowerCase(Locale.ROOT).contains("+json"))) {
            return request;
        }
        byte[] bytes = request.getInputStream().readNBytes(maxJsonBytes + 1);
        if (bytes.length > maxJsonBytes) { throw new TenantInputBodyTooLargeException(); }
        if (bytes.length == 0) { return request; }
        try {
            rejectTenant(JSON.readTree(bytes));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new UntrustedTenantInputException("The user JSON request body is invalid.");
        }
        return new BodyRequest(request, bytes);
    }

    private static boolean reserved(String name) {
        return NAMES.contains(name.replaceAll("[-_]", "").toLowerCase(Locale.ROOT));
    }

    private static void rejectTenant(JsonNode node) {
        if (node == null) { return; }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                if (reserved(name)) { throw new UntrustedTenantInputException(); }
                rejectTenant(node.get(name));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) { rejectTenant(child); }
        }
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BodyRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try {
                        if (!isFinished()) { listener.onDataAvailable(); }
                        if (isFinished()) { listener.onAllDataRead(); }
                    } catch (IOException exception) { listener.onError(exception); }
                }
            };
        }
        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
        }
    }
}

final class UntrustedTenantInputException extends RuntimeException {
    UntrustedTenantInputException() { this("User requests must not supply Tenant context."); }
    UntrustedTenantInputException(String message) { super(message); }
}

final class TenantInputBodyTooLargeException extends RuntimeException {
    TenantInputBodyTooLargeException() { super("The JSON request body exceeds the configured size limit."); }
}
