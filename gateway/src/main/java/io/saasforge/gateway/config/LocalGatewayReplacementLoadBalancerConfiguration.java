package io.saasforge.gateway.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/**
 * 本机 Gateway 不可依赖 Docker 内部 IP；仅在替换生命周期显式启用时，将正式服务 ID 映射到 Compose 的回环发布端口。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "saasforge.local-replacement.enabled", havingValue = "true")
@LoadBalancerClients({
        @LoadBalancerClient(name = LocalGatewayReplacementLoadBalancerConfiguration.IAM_SERVICE_ID,
                configuration = LocalGatewayReplacementLoadBalancerConfiguration.IamConfiguration.class),
        @LoadBalancerClient(name = LocalGatewayReplacementLoadBalancerConfiguration.TENANT_ACCESS_SERVICE_ID,
                configuration = LocalGatewayReplacementLoadBalancerConfiguration.TenantAccessConfiguration.class),
        @LoadBalancerClient(name = LocalGatewayReplacementLoadBalancerConfiguration.ENTITLEMENT_SERVICE_ID,
                configuration = LocalGatewayReplacementLoadBalancerConfiguration.EntitlementConfiguration.class)
})
class LocalGatewayReplacementLoadBalancerConfiguration {
    static final String IAM_SERVICE_ID = "iam-service";
    static final String TENANT_ACCESS_SERVICE_ID = "tenant-access-service";
    static final String ENTITLEMENT_SERVICE_ID = "entitlement-service";

    static ServiceInstanceListSupplier supplier(String serviceId, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("本机服务端口不合法");
        }
        ServiceInstance instance = new DefaultServiceInstance(
                serviceId + "-local", serviceId, "127.0.0.1", port, false);
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return serviceId;
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(List.of(instance));
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "saasforge.local-replacement.enabled", havingValue = "true")
    static class IamConfiguration {
        @Bean
        ServiceInstanceListSupplier iamServiceInstanceListSupplier(
                @Value("${saasforge.local-replacement.iam-service-port}") int port) {
            return supplier(IAM_SERVICE_ID, port);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "saasforge.local-replacement.enabled", havingValue = "true")
    static class TenantAccessConfiguration {
        @Bean
        ServiceInstanceListSupplier tenantAccessServiceInstanceListSupplier(
                @Value("${saasforge.local-replacement.tenant-access-service-port}") int port) {
            return supplier(TENANT_ACCESS_SERVICE_ID, port);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "saasforge.local-replacement.enabled", havingValue = "true")
    static class EntitlementConfiguration {
        @Bean
        ServiceInstanceListSupplier entitlementServiceInstanceListSupplier(
                @Value("${saasforge.local-replacement.entitlement-service-port}") int port) {
            return supplier(ENTITLEMENT_SERVICE_ID, port);
        }
    }
}
