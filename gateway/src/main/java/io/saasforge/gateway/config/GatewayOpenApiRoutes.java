package io.saasforge.gateway.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 当前 OpenAPI v1 的公开路由白名单；服务发现和部署配置都不能增加公开入口。
 */
final class GatewayOpenApiRoutes {

    private static final String ROUTE_METADATA = "/META-INF/saasforge/gateway-openapi-routes.tsv";
    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();
    private static final List<Route> ROUTES = loadRoutes();

    private GatewayOpenApiRoutes() {
    }

    static List<Route> routes() {
        return ROUTES;
    }

    static List<Route> matching(String requestPath) {
        PathContainer path = PathContainer.parsePath(requestPath);
        return ROUTES.stream().filter(route -> route.pattern().matches(path)).toList();
    }

    private static List<Route> loadRoutes() {
        try (InputStream input = GatewayOpenApiRoutes.class.getResourceAsStream(ROUTE_METADATA)) {
            if (input == null) {
                throw new IllegalStateException("缺少生成的 Gateway OpenAPI 路由元数据");
            }
            List<Route> routes = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 5) {
                        throw new IllegalStateException("Gateway OpenAPI 路由元数据格式不合法");
                    }
                    routes.add(new Route(
                            fields[0],
                            HttpMethod.valueOf(fields[1]),
                            fields[2],
                            Target.valueOf(fields[3]),
                            UserTokenRequirement.valueOf(fields[4]),
                            PATH_PATTERN_PARSER.parse(fields[2])));
                }
            }
            if (routes.isEmpty()) {
                throw new IllegalStateException("Gateway OpenAPI 路由元数据不能为空");
            }
            return List.copyOf(routes);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("无法加载生成的 Gateway OpenAPI 路由元数据", exception);
        }
    }

    record Route(
            String operationId,
            HttpMethod method,
            String path,
            Target target,
            UserTokenRequirement userTokenRequirement,
            PathPattern pattern) {
    }

    enum UserTokenRequirement {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    enum Target {
        IAM("iam-service"),
        TENANT_ACCESS("tenant-access-service"),
        ENTITLEMENT("entitlement-service");

        private final String serviceId;

        Target(String serviceId) {
            this.serviceId = serviceId;
        }

        String serviceId() {
            return serviceId;
        }
    }
}
