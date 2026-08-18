package io.saasforge.gateway.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter.RequestHttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * 代理出站请求的最终头边界：客户端不能借转发头伪造来源，Trace Context 是唯一的关联头。
 */
@Component
class GatewayRequestHeadersFilter implements RequestHttpHeadersFilter, Ordered {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade");

    @Override
    public HttpHeaders apply(HttpHeaders input, ServerRequest request) {
        Set<String> connectionHeaders = connectionHeaders(request.headers().asHttpHeaders());
        HttpHeaders filtered = new HttpHeaders();
        input.headerSet().forEach(entry -> {
            if (isEndToEndHeader(entry.getKey(), connectionHeaders)) {
                filtered.addAll(entry.getKey(), entry.getValue());
            }
        });
        applyTraceContext(filtered, input, TraceContext.current(request.servletRequest()));
        return filtered;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private boolean isEndToEndHeader(String name, Set<String> connectionHeaders) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return !HOP_BY_HOP_HEADERS.contains(normalized) && !connectionHeaders.contains(normalized)
                && !HttpHeaders.HOST.equalsIgnoreCase(name) && !"forwarded".equals(normalized)
                && !normalized.startsWith("x-forwarded-") && !TraceContext.TRACEPARENT_HEADER.equals(normalized)
                && !TraceContext.TRACESTATE_HEADER.equals(normalized);
    }

    private Set<String> connectionHeaders(HttpHeaders headers) {
        Set<String> result = new HashSet<>();
        headers.getOrEmpty(HttpHeaders.CONNECTION).forEach(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.toLowerCase(Locale.ROOT))
                .forEach(result::add));
        return result;
    }

    private void applyTraceContext(HttpHeaders target, HttpHeaders input, TraceContext context) {
        target.set(TraceContext.TRACEPARENT_HEADER, context.traceparent());
        if (context.continued()) {
            List<String> tracestate = input.getOrEmpty(TraceContext.TRACESTATE_HEADER);
            if (!tracestate.isEmpty()) {
                target.put(TraceContext.TRACESTATE_HEADER, List.copyOf(tracestate));
            }
        }
    }
}
