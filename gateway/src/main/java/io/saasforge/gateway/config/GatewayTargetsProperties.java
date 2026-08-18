package io.saasforge.gateway.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 只从部署配置获取领域服务地址，公开路由不能由部署配置扩展。
 */
@ConfigurationProperties("gateway.targets")
public record GatewayTargetsProperties(URI iam, URI tenantAccess, URI entitlement) {
}
