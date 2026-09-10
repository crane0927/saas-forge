package io.saasforge.entitlement.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import io.saasforge.discovery.DiscoveredGrpcChannel;
import io.saasforge.discovery.NacosServiceEndpoints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 本地文件仅决定本实例的注册信息，下游地址由 Nacos 按调用发现。 */
@Configuration(proxyBeanMethods = false)
@Profile("local")
public class LocalServiceDiscoveryConfiguration {
    @Bean
    NacosServiceEndpoints localServiceEndpoints(NacosServiceManager manager, NacosDiscoveryProperties properties) {
        return new NacosServiceEndpoints(manager.getNamingService(), properties.getGroup());
    }

    @Bean
    DiscoveredGrpcChannel iamServiceChannel(NacosServiceEndpoints endpoints) {
        return new DiscoveredGrpcChannel(endpoints, "iam-service");
    }

    @Bean
    DiscoveredGrpcChannel tenantAccessServiceChannel(NacosServiceEndpoints endpoints) {
        return new DiscoveredGrpcChannel(endpoints, "tenant-access-service");
    }

    @Bean
    org.springframework.web.client.RestClient entitlementIamRestClient(NacosServiceEndpoints endpoints) {
        return endpoints.httpClient("iam-service");
    }
}
