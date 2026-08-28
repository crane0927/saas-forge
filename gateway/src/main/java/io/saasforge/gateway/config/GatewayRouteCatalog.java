package io.saasforge.gateway.config;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.contracts.route.HttpRouteCatalogLoader;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Gateway 对共享不可变 Route Catalog 的路径匹配视图。 */
final class GatewayRouteCatalog {

    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();
    private static final List<Route> ROUTES = HttpRouteCatalogLoader.load().routes().stream()
            .map(GatewayRouteCatalog::route)
            .toList();

    private GatewayRouteCatalog() {
    }

    static List<Route> routes() {
        return ROUTES;
    }

    static List<Route> matching(String requestPath) {
        PathContainer path = PathContainer.parsePath(requestPath);
        return ROUTES.stream().filter(route -> route.pattern().matches(path)).toList();
    }

    private static Route route(HttpRouteCatalog.Route route) {
        return new Route(
                route.operationId(),
                HttpMethod.valueOf(route.method().name()),
                route.path(),
                route.serviceId(),
                route.credentialRequirement(),
                PATH_PATTERN_PARSER.parse(route.path()));
    }

    record Route(
            String operationId,
            HttpMethod method,
            String path,
            String serviceId,
            HttpRouteCatalog.CredentialRequirement credentialRequirement,
            PathPattern pattern) {
    }
}
