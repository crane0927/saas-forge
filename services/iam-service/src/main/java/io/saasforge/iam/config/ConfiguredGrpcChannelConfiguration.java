package io.saasforge.iam.config;

import io.grpc.Channel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.grpc.client.GrpcChannelFactory;

/** 非本地入口保留既有部署的通道配置；连接生命周期由 Spring gRPC 管理。 */
@Configuration(proxyBeanMethods = false)
@Profile("!local")
public class ConfiguredGrpcChannelConfiguration {
    @Bean
    Channel tenantAccessMembershipChannel(GrpcChannelFactory channels,
            @org.springframework.beans.factory.annotation.Value("${saasforge.iam.tenant-access-grpc-target:tenant-access}") String target) {
        return channels.createChannel(target);
    }
}
