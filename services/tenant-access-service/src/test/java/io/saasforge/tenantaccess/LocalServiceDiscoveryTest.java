package io.saasforge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import io.grpc.Channel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandResponse;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saasforge.tenantaccess.config.LocalServiceDiscoveryConfiguration;
import io.saasforge.tenantaccess.infrastructure.grpc.GrpcInitializationQuotaGateway;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalServiceDiscoveryTest {
    @Test
    void initializationQuotaReachesChangedEndpointAndRejectsDiscoveryFailure() throws Exception {
        NamingService naming = mock(NamingService.class);
        NacosServiceManager manager = mock(NacosServiceManager.class);
        when(manager.getNamingService()).thenReturn(naming);
        AtomicReference<QuotaCommandRequest> firstRequest = new AtomicReference<>();
        AtomicReference<QuotaCommandRequest> secondRequest = new AtomicReference<>();
        Server first = server(firstRequest);
        Server second = server(secondRequest);
        when(naming.selectInstances("entitlement-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(instance(first.getPort())))
                .thenReturn(List.of(instance(second.getPort())))
                .thenReturn(List.of())
                .thenThrow(new com.alibaba.nacos.api.exception.NacosException(500, "offline"));
        try {
            new ApplicationContextRunner()
                    .withPropertyValues("spring.profiles.active=local")
                    .withUserConfiguration(LocalServiceDiscoveryConfiguration.class)
                    .withBean(NacosServiceManager.class, () -> manager)
                    .withInitializer(application -> {
                        NacosDiscoveryProperties properties = mock(NacosDiscoveryProperties.class);
                        when(properties.getGroup()).thenReturn("DEFAULT_GROUP");
                        application.getBeanFactory().registerSingleton("nacosDiscoveryProperties", properties);
                    })
                    .run(context -> {
                        Channel channel = context.getBean("entitlementServiceChannel", Channel.class);
                        var gateway = new GrpcInitializationQuotaGateway(
                                QuotaCommandServiceGrpc.newBlockingStub(channel), () -> "service-token");
                        UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000001");
                        UUID operationId = UUID.fromString("019535d9-0000-7000-8000-000000000002");
                        gateway.consume(tenantId, operationId);
                        gateway.consume(tenantId, operationId);
                        assertThat(firstRequest.get().getTenantId()).isEqualTo(tenantId.toString());
                        assertThat(secondRequest.get()).isEqualTo(firstRequest.get());
                        assertThat(secondRequest.get().getQuotaCode()).isEqualTo("max_users");
                        assertThat(secondRequest.get().getAmount()).isEqualTo(1);
                        assertThatThrownBy(() -> gateway.consume(tenantId, operationId))
                                .isInstanceOf(RemoteWorkflowUnavailableException.class);
                        assertThatThrownBy(() -> gateway.release(tenantId, operationId))
                                .isInstanceOf(RemoteWorkflowUnavailableException.class);
                    });
        } finally {
            first.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            second.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static Instance instance(int port) {
        Instance instance = new Instance();
        instance.setIp("127.0.0.1");
        instance.setPort(1);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setMetadata(Map.of("grpc.port", Integer.toString(port)));
        return instance;
    }

    private static Server server(AtomicReference<QuotaCommandRequest> received) throws Exception {
        return ServerBuilder.forPort(0).addService(new QuotaCommandServiceGrpc.QuotaCommandServiceImplBase() {
            @Override
            public void consume(QuotaCommandRequest request, StreamObserver<QuotaCommandResponse> observer) {
                received.set(request);
                observer.onNext(QuotaCommandResponse.getDefaultInstance());
                observer.onCompleted();
            }
        }).build().start();
    }
}
