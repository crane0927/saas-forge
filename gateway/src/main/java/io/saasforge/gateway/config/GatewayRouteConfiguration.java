package io.saasforge.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.http.HttpMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayTargetsProperties.class)
class GatewayRouteConfiguration {

    private static final String IAM_SERVICE_ID = "iam-service";

    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(GatewayTargetsProperties targets) {
        RouterFunction<ServerResponse> routes = null;
        for (GatewayOpenApiRoutes.Route route : GatewayOpenApiRoutes.routes()) {
            RouterFunction<ServerResponse> gatewayRoute = gatewayRoute(route, targets);
            routes = routes == null ? gatewayRoute : routes.and(gatewayRoute);
        }
        if (routes == null) {
            throw new IllegalStateException("OpenAPI route whitelist must not be empty");
        }
        return routes;
    }

    private RouterFunction<ServerResponse> gatewayRoute(GatewayOpenApiRoutes.Route route,
            GatewayTargetsProperties targets) {
        var builder = route(route.operationId());
        if (route.method() == HttpMethod.GET) {
            builder.GET(route.path(), http());
        } else if (route.method() == HttpMethod.POST) {
            builder.POST(route.path(), http());
        } else if (route.method() == HttpMethod.DELETE) {
            builder.DELETE(route.path(), http());
        } else {
            throw new IllegalStateException("Unsupported OpenAPI HTTP method: " + route.method());
        }
        return switch (route.target()) {
            case IAM -> builder.filter(lb(IAM_SERVICE_ID)).build();
            case TENANT_ACCESS, ENTITLEMENT -> builder.before(uri(route.target().resolveDeploymentTarget(targets))).build();
        };
    }
}
