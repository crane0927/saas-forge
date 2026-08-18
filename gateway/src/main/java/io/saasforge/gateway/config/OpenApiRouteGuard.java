package io.saasforge.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 路由函数只匹配合法方法；该守卫将其余请求转换为公开契约承诺的错误响应。
 */
@Component
class OpenApiRouteGuard extends OncePerRequestFilter {

    private final GatewayProblemDetailsWriter problemDetailsWriter;

    OpenApiRouteGuard(GatewayProblemDetailsWriter problemDetailsWriter) {
        this.problemDetailsWriter = problemDetailsWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<GatewayOpenApiRoutes.Route> routes = GatewayOpenApiRoutes.matching(requestPath(request));
        if (routes.isEmpty()) {
            writeProblem(request, response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "The requested route is not declared.");
            return;
        }
        if (routes.stream().noneMatch(route -> route.method().matches(request.getMethod()))) {
            writeProblem(request, response, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "The requested method is not declared for this route.");
            response.setHeader("Allow", routes.stream().map(route -> route.method().name()).sorted()
                    .reduce((left, right) -> left + ", " + right).orElseThrow());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String requestPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return request.getRequestURI().substring(contextPath.length());
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
            String detail)
            throws IOException {
        problemDetailsWriter.write(request, response, status, code, detail);
    }
}
