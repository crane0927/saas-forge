package io.saasforge.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayTargetsProperties.class)
class GatewayRouteConfiguration {

    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(GatewayTargetsProperties targets) {
        return route("iam-jwks")
                .GET("/.well-known/jwks.json", http())
                .before(uri(targets.iam()))
                .build();
    }
}
