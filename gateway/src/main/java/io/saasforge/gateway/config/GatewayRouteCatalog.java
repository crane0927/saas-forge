package io.saasforge.gateway.config;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.contracts.route.HttpRouteCatalogLoader;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Gateway 对共享不可变 Route Catalog 的路径匹配视图。 */
@Component
final class GatewayRouteCatalog {

    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();
    private final List<Route> routes;

    GatewayRouteCatalog() {
        this(HttpRouteCatalogLoader.load());
    }

    GatewayRouteCatalog(HttpRouteCatalog catalog) {
        this.routes = catalog.routes().stream().map(GatewayRouteCatalog::route).toList();
    }

    List<Route> routes() {
        return routes;
    }

    List<Route> matching(String requestPath) {
        PathContainer path = PathContainer.parsePath(requestPath);
        return routes.stream().filter(route -> route.pattern().matches(path)).toList();
    }

    private static Route route(HttpRouteCatalog.Route route) {
        return new Route(
                route.operationId(),
                HttpMethod.valueOf(route.method().name()),
                route.path(),
                route.serviceId(),
                route.credentialRequirement(),
                route.requiredScopes(),
                PATH_PATTERN_PARSER.parse(route.path()));
    }

    record Route(
            String operationId,
            HttpMethod method,
            String path,
            String serviceId,
            HttpRouteCatalog.CredentialRequirement credentialRequirement,
            List<String> requiredScopes,
            PathPattern pattern) {
    }
}
