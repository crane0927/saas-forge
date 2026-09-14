package io.saasforge.discovery;

import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.ChannelCredentialsProvider;
import org.springframework.grpc.client.ClientInterceptorsConfigurer;
import org.springframework.grpc.client.DefaultGrpcChannelFactory;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;

/** Nacos 提供实例地址，Spring gRPC 保留命名通道的 TLS、拦截器和调用配置。 */
public final class NacosGrpcChannels {
    private final ChannelCredentialsProvider credentials;
    private final ClientInterceptorsConfigurer interceptors;
    private final List<GrpcChannelBuilderCustomizer<?>> customizers;

    public NacosGrpcChannels(ChannelCredentialsProvider credentials, ClientInterceptorsConfigurer interceptors,
            List<GrpcChannelBuilderCustomizer<?>> customizers) {
        this.credentials = credentials;
        this.interceptors = interceptors;
        this.customizers = List.copyOf(customizers);
    }

    public DiscoveredGrpcChannel channel(NacosServiceEndpoints endpoints, String serviceId, String channelName) {
        // 在装配时解析 SSL Bundle；缺失证书不能延迟到首次业务请求或回退明文。
        var channelCredentials = credentials.getChannelCredentials(channelName);
        return new DiscoveredGrpcChannel(endpoints, serviceId, (host, port) -> {
            // 每个实际连接有独立工厂，旧连接关闭后不会被长期工厂的历史列表持有。
            var factory = new DefaultGrpcChannelFactory<>(List.of(
                    (name, builder) -> builder.overrideAuthority(serviceId), this::customize), interceptors);
            factory.setCredentialsProvider(ignored -> channelCredentials);
            factory.setVirtualTargets(ignored -> target(host, port));
            return factory.createChannel(channelName, ChannelBuilderOptions.defaults());
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void customize(String name, ManagedChannelBuilder builder) {
        for (GrpcChannelBuilderCustomizer customizer : customizers) customizer.customize(name, builder);
    }

    private static String target(String host, int port) {
        try {
            return "dns:///" + new URI(null, null, host, port, null, null, null).getRawAuthority();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid discovered gRPC address", exception);
        }
    }
}
