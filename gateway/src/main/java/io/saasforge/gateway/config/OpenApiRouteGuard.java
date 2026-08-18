package io.saasforge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 路由函数只匹配合法方法；该守卫将其余请求转换为公开契约承诺的错误响应。
 */
@Component
class OpenApiRouteGuard extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<GatewayOpenApiRoutes.Route> routes = GatewayOpenApiRoutes.matching(requestPath(request));
        if (routes.isEmpty()) {
            writeProblem(response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "The requested route is not declared.");
            return;
        }
        if (routes.stream().noneMatch(route -> route.method().matches(request.getMethod()))) {
            response.setHeader("Allow", routes.stream().map(route -> route.method().name()).sorted()
                    .reduce((left, right) -> left + ", " + right).orElseThrow());
            writeProblem(response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "The requested method is not declared for this route.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String requestPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return request.getRequestURI().substring(contextPath.length());
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String title = status == HttpStatus.NOT_FOUND ? "Route not found" : "Method not allowed";
        String type = "urn:saasforge:problem:" + code.toLowerCase().replace('_', '-');
        String body = ("{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\","
                + "\"detail\":\"%s\",\"traceId\":\"%s\"}").formatted(type, title, status.value(), code, detail, traceId);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
