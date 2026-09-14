package io.saasforge.starter.security;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.contracts.route.HttpRouteCatalogLoader;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 当前 Spring Boot 服务对共享 Route Catalog 的只读认证视图。 */
final class ReceiverRouteCatalog {

    private static final Pattern SERVICE_ID = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final PathPatternParser PATHS = new PathPatternParser();
    private final List<Route> routes;

    ReceiverRouteCatalog(HttpRouteCatalog catalog, String serviceId) {
        if (catalog == null || catalog.schemaVersion() != HttpRouteCatalogLoader.SUPPORTED_SCHEMA_VERSION
                || catalog.routes() == null || catalog.routes().isEmpty()) {
            throw new IllegalStateException("HTTP Route Catalog 缺失或版本非法");
        }
        if (serviceId == null || !SERVICE_ID.matcher(serviceId).matches()) {
            throw new IllegalStateException("spring.application.name 不是合法 serviceId");
        }
        this.routes = catalog.routes().stream()
                .filter(route -> serviceId.equals(route.serviceId()))
                .map(ReceiverRouteCatalog::route)
                .toList();
        if (routes.isEmpty()) {
            throw new IllegalStateException("当前服务与 HTTP Route Catalog 路由归属不匹配: " + serviceId);
        }
    }

    Route matching(String method, String requestPath) {
        PathContainer path = PathContainer.parsePath(requestPath);
        return routes.stream()
                .filter(route -> route.method().matches(method) && route.pattern().matches(path))
                .findFirst()
                .orElse(null);
    }

    private static Route route(HttpRouteCatalog.Route route) {
        return new Route(
                HttpMethod.valueOf(route.method().name()),
                route.credentialRequirement(),
                route.requiredScopes(),
                PATHS.parse(route.path()));
    }

    record Route(
            HttpMethod method,
            HttpRouteCatalog.CredentialRequirement credentialRequirement,
            List<String> requiredScopes,
            PathPattern pattern) {
    }
}
