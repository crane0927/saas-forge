package io.saasforge.gateway.config;

import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.http.server.PathContainer;

/**
 * 当前 OpenAPI v1 的公开路由白名单；服务发现和部署配置都不能增加公开入口。
 */
final class GatewayOpenApiRoutes {

    private static final PathPatternParser PATH_PATTERN_PARSER = new PathPatternParser();

    private static final List<Route> ROUTES = List.of(
            route("login", HttpMethod.POST, "/api/v1/auth/login", Target.IAM),
            route("password-change", HttpMethod.POST, "/api/v1/auth/password-changes", Target.IAM),
            route("refreshAccessToken", HttpMethod.POST, "/api/v1/auth/refresh", Target.IAM),
            route("logout", HttpMethod.POST, "/api/v1/auth/logout", Target.IAM),
            route("switchTenantContext", HttpMethod.POST, "/api/v1/auth/tenant-switches", Target.IAM),
            route("issueClientCredentialsToken", HttpMethod.POST, "/oauth2/token", Target.IAM),
            route("getJwks", HttpMethod.GET, "/.well-known/jwks.json", Target.IAM),
            route("createPlatformTenant", HttpMethod.POST, "/api/v1/platform/tenants", Target.TENANT_ACCESS),
            route("initializeTenantAdministrator", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/administrator-initializations", Target.TENANT_ACCESS),
            route("suspendTenant", HttpMethod.POST, "/api/v1/platform/tenants/{tenantId}/suspensions", Target.TENANT_ACCESS),
            route("resumeTenant", HttpMethod.DELETE, "/api/v1/platform/tenants/{tenantId}/suspensions", Target.TENANT_ACCESS),
            route("createQuotaDefinition", HttpMethod.POST, "/api/v1/platform/quota-definitions", Target.ENTITLEMENT),
            route("activateQuotaDefinition", HttpMethod.POST,
                    "/api/v1/platform/quota-definitions/{quotaDefinitionId}/activations", Target.ENTITLEMENT),
            route("createPlan", HttpMethod.POST, "/api/v1/platform/plans", Target.ENTITLEMENT),
            route("activatePlan", HttpMethod.POST, "/api/v1/platform/plans/{planId}/activations", Target.ENTITLEMENT),
            route("createInitialSubscription", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/subscriptions", Target.ENTITLEMENT),
            route("createOAuthClient", HttpMethod.POST, "/api/v1/platform/oauth-clients", Target.IAM),
            route("rotateOAuthClientSecret", HttpMethod.POST,
                    "/api/v1/platform/oauth-clients/{clientId}/secret-rotations", Target.IAM),
            route("revokeOAuthClient", HttpMethod.POST,
                    "/api/v1/platform/oauth-clients/{clientId}/revocations", Target.IAM));

    private GatewayOpenApiRoutes() {
    }

    static List<Route> routes() {
        return ROUTES;
    }

    static List<Route> matching(String requestPath) {
        PathContainer path = PathContainer.parsePath(requestPath);
        return ROUTES.stream().filter(route -> route.pattern().matches(path)).toList();
    }

    private static Route route(String operationId, HttpMethod method, String path, Target target) {
        return new Route(operationId, method, path, target, PATH_PATTERN_PARSER.parse(path));
    }

    record Route(String operationId, HttpMethod method, String path, Target target, PathPattern pattern) {
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
