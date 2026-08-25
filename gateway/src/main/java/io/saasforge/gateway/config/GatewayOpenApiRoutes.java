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
            none("login", HttpMethod.POST, "/api/v1/auth/login", Target.IAM),
            none("changeInitialPassword", HttpMethod.POST, "/api/v1/auth/password-changes", Target.IAM),
            none("establishPassword", HttpMethod.POST, "/api/v1/auth/password-setups", Target.IAM),
            none("refreshAccessToken", HttpMethod.POST, "/api/v1/auth/refresh", Target.IAM),
            none("selectAuthenticationContext", HttpMethod.POST, "/api/v1/auth/context-selections", Target.IAM),
            optional("logout", HttpMethod.POST, "/api/v1/auth/logout", Target.IAM),
            none("switchTenantContext", HttpMethod.POST, "/api/v1/auth/tenant-switches", Target.IAM),
            none("issueClientCredentialsToken", HttpMethod.POST, "/oauth2/token", Target.IAM),
            none("getJwks", HttpMethod.GET, "/.well-known/jwks.json", Target.IAM),
            required("createPlatformTenant", HttpMethod.POST, "/api/v1/platform/tenants", Target.TENANT_ACCESS),
            required("initializeTenantAdministrator", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/administrator-initializations", Target.TENANT_ACCESS),
            required("resendTenantAdministratorPasswordSetup", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/administrator-password-setups", Target.TENANT_ACCESS),
            required("suspendTenant", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/suspensions", Target.TENANT_ACCESS),
            required("resumeTenant", HttpMethod.DELETE,
                    "/api/v1/platform/tenants/{tenantId}/suspensions", Target.TENANT_ACCESS),
            required("recoverTenantSuspension", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/suspension-recoveries", Target.TENANT_ACCESS),
            required("createQuotaDefinition", HttpMethod.POST,
                    "/api/v1/platform/quota-definitions", Target.ENTITLEMENT),
            required("activateQuotaDefinition", HttpMethod.POST,
                    "/api/v1/platform/quota-definitions/{quotaDefinitionId}/activations", Target.ENTITLEMENT),
            required("createPlan", HttpMethod.POST, "/api/v1/platform/plans", Target.ENTITLEMENT),
            required("activatePlan", HttpMethod.POST,
                    "/api/v1/platform/plans/{planId}/activations", Target.ENTITLEMENT),
            required("createInitialSubscription", HttpMethod.POST,
                    "/api/v1/platform/tenants/{tenantId}/subscriptions", Target.ENTITLEMENT),
            required("createOAuthClient", HttpMethod.POST, "/api/v1/platform/oauth-clients", Target.IAM),
            required("rotateOAuthClientSecret", HttpMethod.POST,
                    "/api/v1/platform/oauth-clients/{clientId}/secret-rotations", Target.IAM),
            required("revokeOAuthClient", HttpMethod.POST,
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

    private static Route none(String operationId, HttpMethod method, String path, Target target) {
        return route(operationId, method, path, target, UserTokenRequirement.NONE);
    }

    private static Route optional(String operationId, HttpMethod method, String path, Target target) {
        return route(operationId, method, path, target, UserTokenRequirement.OPTIONAL);
    }

    private static Route required(String operationId, HttpMethod method, String path, Target target) {
        return route(operationId, method, path, target, UserTokenRequirement.REQUIRED);
    }

    private static Route route(
            String operationId,
            HttpMethod method,
            String path,
            Target target,
            UserTokenRequirement userTokenRequirement) {
        return new Route(
                operationId, method, path, target, userTokenRequirement, PATH_PATTERN_PARSER.parse(path));
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
