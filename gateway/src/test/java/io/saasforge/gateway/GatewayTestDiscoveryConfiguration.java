package io.saasforge.gateway;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

@TestConfiguration(proxyBeanMethods = false)
@LoadBalancerClient(name = GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID,
        configuration = GatewayTestDiscoveryConfiguration.IamLoadBalancerConfiguration.class)
class GatewayTestDiscoveryConfiguration {

    static final String IAM_SERVICE_ID = "iam-service";

    private static final AtomicReference<List<ServiceInstance>> IAM_INSTANCES = new AtomicReference<>(List.of());

    static void discoverIamAt(URI uri) {
        IAM_INSTANCES.set(List.of(new DefaultServiceInstance("iam-test", IAM_SERVICE_ID, uri.getHost(), uri.getPort(), false)));
    }

    static void clearIamInstances() {
        IAM_INSTANCES.set(List.of());
    }

    @Configuration(proxyBeanMethods = false)
    static class IamLoadBalancerConfiguration {

        @Bean
        ServiceInstanceListSupplier iamServiceInstanceListSupplier() {
            return new ServiceInstanceListSupplier() {
                @Override
                public String getServiceId() {
                    return IAM_SERVICE_ID;
                }

                @Override
                public Flux<List<ServiceInstance>> get() {
                    return Flux.defer(() -> Flux.just(IAM_INSTANCES.get()));
                }
            };
        }
    }
}
