package io.saasforge.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Channel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityResponse;
import io.saasforge.contracts.tenantaccess.provisioning.v1.InitialSubscriptionEligibility;
import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saasforge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import io.saasforge.entitlement.config.LocalServiceDiscoveryConfiguration;
import io.saasforge.entitlement.infrastructure.grpc.GrpcTenantEligibilityGateway;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class LocalServiceDiscoveryTest {
    @Test
    void iamHttpClientFollowsDiscoveryAndRejectsMissingHealthyTargets() throws Exception {
        NamingService naming = mock(NamingService.class);
        HttpServer first = httpServer("first");
        HttpServer second = httpServer("second");
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(instance(first.getAddress().getPort(), 1)))
                .thenReturn(List.of(instance(second.getAddress().getPort(), 1)))
                .thenReturn(List.of());
        try {
            context(naming).run(context -> {
                assertThat(context).hasNotFailed();
                RestClient iam = context.getBean("entitlementIamRestClient", RestClient.class);
                assertThat(iam.get().uri("/.well-known/jwks.json").retrieve().body(String.class)).isEqualTo("first");
                assertThat(iam.get().uri("/.well-known/jwks.json").retrieve().body(String.class)).isEqualTo("second");
                assertThatThrownBy(() -> iam.get().uri("/.well-known/jwks.json").retrieve().body(String.class))
                        .isInstanceOf(RuntimeException.class);
            });
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    @Test
    void tenantEligibilityChannelFollowsMetadataAndFailsClosed() throws Exception {
        NamingService naming = mock(NamingService.class);
        Server first = grpcServer(InitialSubscriptionEligibility.PENDING_ELIGIBLE);
        Server second = grpcServer(InitialSubscriptionEligibility.INVALID_STATE);
        when(naming.selectInstances("tenant-access-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(instance(1, first.getPort())))
                .thenReturn(List.of(instance(1, second.getPort())))
                .thenReturn(List.of())
                .thenThrow(new com.alibaba.nacos.api.exception.NacosException(500, "unavailable"));
        try {
            context(naming).run(context -> {
                Channel channel = context.getBean("tenantAccessServiceChannel", Channel.class);
                var gateway = new GrpcTenantEligibilityGateway(
                        TenantProvisioningQueryServiceGrpc.newBlockingStub(channel), () -> "service-token");
                UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000001");
                assertThat(gateway.checkInitialSubscription(tenantId)).isEqualTo(TenantEligibilityGateway.Outcome.PENDING_ELIGIBLE);
                assertThat(gateway.checkInitialSubscription(tenantId)).isEqualTo(TenantEligibilityGateway.Outcome.INVALID_STATE);
                assertThatThrownBy(() -> gateway.checkInitialSubscription(tenantId))
                        .isInstanceOf(TenantEligibilityUnavailableException.class);
                assertThatThrownBy(() -> gateway.checkInitialSubscription(tenantId))
                        .isInstanceOf(TenantEligibilityUnavailableException.class);
            });
        } finally {
            first.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            second.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static ApplicationContextRunner context(NamingService naming) {
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(naming);
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=local")
                .withUserConfiguration(LocalServiceDiscoveryConfiguration.class)
                .withBean(NacosServiceManager.class, () -> manager)
                .withInitializer(application -> {
                    NacosDiscoveryProperties properties = mock(NacosDiscoveryProperties.class);
                    when(properties.getGroup()).thenReturn("DEFAULT_GROUP");
                    application.getBeanFactory().registerSingleton("nacosDiscoveryProperties", properties);
                });
    }

    private static Instance instance(int httpPort, int grpcPort) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(httpPort);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setMetadata(Map.of("grpc.port", Integer.toString(grpcPort)));
        return instance;
    }

    private static HttpServer httpServer(String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        return server;
    }

    private static Server grpcServer(InitialSubscriptionEligibility eligibility) throws Exception {
        return ServerBuilder.forPort(0).addService(new TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceImplBase() {
            @Override
            public void checkInitialSubscriptionEligibility(CheckInitialSubscriptionEligibilityRequest request,
                    StreamObserver<CheckInitialSubscriptionEligibilityResponse> observer) {
                observer.onNext(CheckInitialSubscriptionEligibilityResponse.newBuilder().setEligibility(eligibility).build());
                observer.onCompleted();
            }
        }).build().start();
    }
}
