package io.saasforge.gateway.config;

import java.net.URI;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Gateway 只从部署配置获取未接入服务发现的领域服务地址，公开路由不能由部署配置扩展。
 */
@ConfigurationProperties("gateway.targets")
@Validated
public record GatewayTargetsProperties(URI tenantAccess, URI entitlement) {

    @AssertTrue(message = "gateway.targets.tenant-access must be an absolute HTTP(S) URI without a path prefix")
    public boolean isTenantAccessValid() {
        return isDeploymentTarget(tenantAccess);
    }

    @AssertTrue(message = "gateway.targets.entitlement must be an absolute HTTP(S) URI without a path prefix")
    public boolean isEntitlementValid() {
        return isDeploymentTarget(entitlement);
    }

    private static boolean isDeploymentTarget(URI target) {
        return target != null
                && target.isAbsolute()
                && ("http".equalsIgnoreCase(target.getScheme()) || "https".equalsIgnoreCase(target.getScheme()))
                && target.getHost() != null
                && (target.getPath().isEmpty() || "/".equals(target.getPath()))
                && target.getQuery() == null
                && target.getFragment() == null;
    }
}
