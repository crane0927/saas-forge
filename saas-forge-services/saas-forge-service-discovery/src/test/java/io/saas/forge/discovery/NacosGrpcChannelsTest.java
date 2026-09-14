package io.saas.forge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.grpc.client.ClientInterceptorsConfigurer;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;

class NacosGrpcChannelsTest {
    @TempDir
    Path directory;

    @Test
    void preservesTlsServiceIdentityAndNamedChannelCustomizerAfterEndpointChange() throws Exception {
        Path storeFile = directory.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
                "-keystore", storeFile.toString(), "-storepass", "test-password", "-dname", "CN=iam-service",
                "-ext", "SAN=dns:iam-service", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertThat(keytool.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) { store.load(input, "test-password".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var serverCredentials = TlsServerCredentials.newBuilder().keyManager(keys.getKeyManagers()).build();
        var clientCredentials = TlsChannelCredentials.newBuilder().trustManager(trust.getTrustManagers()).build();
        Metadata.Key<String> authorization = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        Metadata headers = new Metadata();
        headers.put(authorization, "Bearer test-service-token");
        GrpcChannelBuilderCustomizer<io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder> customizer = (name, builder) -> {
            assertThat(name).isEqualTo("iam");
            builder.intercept(MetadataUtils.newAttachHeadersInterceptor(headers));
        };
        var factory = new NacosGrpcChannels(name -> {
            assertThat(name).isEqualTo("iam");
            return clientCredentials;
        }, new ClientInterceptorsConfigurer(new StaticApplicationContext()), List.of(customizer));
        var service = ServerServiceDefinition.builder("test.Membership")
                .addMethod(DiscoveredGrpcChannelTest.METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext("secure");
                    observer.onCompleted();
                })).build();
        io.grpc.ServerInterceptor security = new io.grpc.ServerInterceptor() {
            @Override
            public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                    io.grpc.ServerCall<ReqT, RespT> call, Metadata metadata,
                    io.grpc.ServerCallHandler<ReqT, RespT> next) {
                assertThat(metadata.get(authorization)).isEqualTo("Bearer test-service-token");
                return next.startCall(call, metadata);
            }
        };
        Server first = Grpc.newServerBuilderForPort(0, serverCredentials).intercept(security).addService(service).build().start();
        Server second = Grpc.newServerBuilderForPort(0, serverCredentials).intercept(security).addService(service).build().start();
        NamingService naming = mock(NamingService.class);
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(DiscoveredGrpcChannelTest.instance(first.getPort())))
                .thenReturn(List.of(DiscoveredGrpcChannelTest.instance(second.getPort())));
        when(naming.selectInstances("wrong-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(DiscoveredGrpcChannelTest.instance(first.getPort())));
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = factory.channel(endpoints, "iam-service", "iam");
             var wrongIdentity = factory.channel(endpoints, "wrong-service", "iam")) {
            assertThat(DiscoveredGrpcChannelTest.call(channel)).isEqualTo("secure");
            first.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            assertThat(DiscoveredGrpcChannelTest.call(channel)).isEqualTo("secure");
            // 第二个实例的证书也必须匹配服务名，不能因 Nacos 返回 IP 就跳过身份校验。
            when(naming.selectInstances("wrong-service", "DEFAULT_GROUP", true, false))
                    .thenReturn(List.of(DiscoveredGrpcChannelTest.instance(second.getPort())));
            assertThatThrownBy(() -> DiscoveredGrpcChannelTest.call(wrongIdentity))
                    .isInstanceOf(io.grpc.StatusRuntimeException.class);
        } finally {
            first.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            second.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsMissingSecurityMaterialBeforeAnyBusinessCall() {
        var factory = new NacosGrpcChannels(name -> { throw new IllegalStateException("SSL bundle missing"); },
                new ClientInterceptorsConfigurer(new StaticApplicationContext()), List.of());
        try (var endpoints = new NacosServiceEndpoints(mock(NamingService.class), "DEFAULT_GROUP")) {
            assertThatThrownBy(() -> factory.channel(endpoints, "iam-service", "iam"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("SSL bundle missing");
        }
    }

    @Test
    void tlsClientDoesNotFallBackToPlaintext() throws Exception {
        Server server = DiscoveredGrpcChannelTest.server("plaintext");
        NamingService naming = mock(NamingService.class);
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(DiscoveredGrpcChannelTest.instance(server.getPort())));
        var factory = new NacosGrpcChannels(name -> TlsChannelCredentials.create(),
                new ClientInterceptorsConfigurer(new StaticApplicationContext()), List.of());
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = factory.channel(endpoints, "iam-service", "iam")) {
            assertThatThrownBy(() -> DiscoveredGrpcChannelTest.call(channel))
                    .isInstanceOf(io.grpc.StatusRuntimeException.class);
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
