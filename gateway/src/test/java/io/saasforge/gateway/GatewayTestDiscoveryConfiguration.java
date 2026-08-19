package io.saasforge.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

@TestConfiguration(proxyBeanMethods = false)
@LoadBalancerClients({
        @LoadBalancerClient(name = GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID,
                configuration = GatewayTestDiscoveryConfiguration.IamLoadBalancerConfiguration.class),
        @LoadBalancerClient(name = GatewayTestDiscoveryConfiguration.TENANT_ACCESS_SERVICE_ID,
                configuration = GatewayTestDiscoveryConfiguration.TenantAccessLoadBalancerConfiguration.class),
        @LoadBalancerClient(name = GatewayTestDiscoveryConfiguration.ENTITLEMENT_SERVICE_ID,
                configuration = GatewayTestDiscoveryConfiguration.EntitlementLoadBalancerConfiguration.class)
})
class GatewayTestDiscoveryConfiguration {

    static final String IAM_SERVICE_ID = "iam-service";
    static final String TENANT_ACCESS_SERVICE_ID = "tenant-access-service";
    static final String ENTITLEMENT_SERVICE_ID = "entitlement-service";

    private static final Map<String, AtomicReference<List<ServiceInstance>>> INSTANCES = Map.of(
            IAM_SERVICE_ID, new AtomicReference<>(List.of()),
            TENANT_ACCESS_SERVICE_ID, new AtomicReference<>(List.of()),
            ENTITLEMENT_SERVICE_ID, new AtomicReference<>(List.of()));

    static void discoverAt(String serviceId, URI uri) {
        instancesFor(serviceId).set(List.of(new DefaultServiceInstance(
                serviceId + "-test", serviceId, uri.getHost(), uri.getPort(), false)));
    }

    static void clearInstances(String serviceId) {
        instancesFor(serviceId).set(List.of());
    }

    private static AtomicReference<List<ServiceInstance>> instancesFor(String serviceId) {
        AtomicReference<List<ServiceInstance>> instances = INSTANCES.get(serviceId);
        if (instances == null) {
            throw new IllegalArgumentException("Unsupported test service ID: " + serviceId);
        }
        return instances;
    }

    private static ServiceInstanceListSupplier serviceInstanceListSupplier(String serviceId) {
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return serviceId;
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.defer(() -> Flux.just(instancesFor(serviceId).get()));
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class IamLoadBalancerConfiguration {

        @Bean
        ServiceInstanceListSupplier iamServiceInstanceListSupplier() {
            return serviceInstanceListSupplier(IAM_SERVICE_ID);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TenantAccessLoadBalancerConfiguration {

        @Bean
        ServiceInstanceListSupplier tenantAccessServiceInstanceListSupplier() {
            return serviceInstanceListSupplier(TENANT_ACCESS_SERVICE_ID);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EntitlementLoadBalancerConfiguration {

        @Bean
        ServiceInstanceListSupplier entitlementServiceInstanceListSupplier() {
            return serviceInstanceListSupplier(ENTITLEMENT_SERVICE_ID);
        }
    }
}
