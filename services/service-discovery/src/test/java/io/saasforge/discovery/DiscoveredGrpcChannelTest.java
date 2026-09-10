package io.saasforge.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DiscoveredGrpcChannelTest {
    static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("test.Membership/check")
            .setRequestMarshaller(new TextMarshaller()).setResponseMarshaller(new TextMarshaller()).build();

    @Test
    void existingStubFollowsGrpcMetadataWhenRegisteredPortChanges() throws Exception {
        NamingService naming = mock(NamingService.class);
        Server first = server("first");
        Server second = server("second");
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = new DiscoveredGrpcChannel(endpoints,
                "tenant-access-service")) {
            when(naming.selectInstances("tenant-access-service", "DEFAULT_GROUP", true, false))
                    .thenReturn(List.of(instance(first.getPort())))
                    .thenReturn(List.of(instance(second.getPort())));
            assertThat(call(channel)).isEqualTo("first");
            assertThat(call(channel)).isEqualTo("second");
        } finally {
            first.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            second.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsUnavailableDiscoveryEvenWhilePreviousConnectionIsAlive() throws Exception {
        NamingService naming = mock(NamingService.class);
        Server server = server("available");
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = new DiscoveredGrpcChannel(endpoints,
                "tenant-access-service")) {
            when(naming.selectInstances("tenant-access-service", "DEFAULT_GROUP", true, false))
                    .thenReturn(List.of(instance(server.getPort())))
                    .thenReturn(List.of())
                    .thenThrow(new com.alibaba.nacos.api.exception.NacosException(500, "offline"));
            assertThat(call(channel)).isEqualTo("available");
            assertThatThrownBy(() -> call(channel)).isInstanceOf(io.grpc.StatusRuntimeException.class)
                    .satisfies(error -> assertThat(io.grpc.Status.fromThrowable(error).getCode())
                            .isEqualTo(io.grpc.Status.Code.UNAVAILABLE));
            assertThatThrownBy(() -> call(channel)).isInstanceOf(io.grpc.StatusRuntimeException.class)
                    .satisfies(error -> assertThat(io.grpc.Status.fromThrowable(error).getCode())
                            .isEqualTo(io.grpc.Status.Code.UNAVAILABLE));
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "65536", "invalid"})
    void rejectsMissingOrInvalidGrpcPortWithoutUsingHttpPort(String port) throws Exception {
        NamingService naming = mock(NamingService.class);
        Instance instance = NacosServiceEndpointsTest.instance(8080);
        instance.setMetadata(port == null ? Map.of() : Map.of("grpc.port", port));
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false)).thenReturn(List.of(instance));
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = new DiscoveredGrpcChannel(endpoints, "iam-service")) {
            assertThatThrownBy(() -> call(channel)).isInstanceOf(io.grpc.StatusRuntimeException.class)
                    .satisfies(error -> assertThat(io.grpc.Status.fromThrowable(error).getCode())
                            .isEqualTo(io.grpc.Status.Code.UNAVAILABLE));
        }
    }

    @Test
    void preservesServiceAuthorizationMetadataAndServerRejection() throws Exception {
        NamingService naming = mock(NamingService.class);
        io.grpc.Metadata.Key<String> authorization = io.grpc.Metadata.Key.of(
                "authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER);
        io.grpc.ServerInterceptor security = new io.grpc.ServerInterceptor() {
            public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                    io.grpc.ServerCall<ReqT, RespT> call, io.grpc.Metadata headers,
                    io.grpc.ServerCallHandler<ReqT, RespT> next) {
                if (!"Bearer service-token".equals(headers.get(authorization))) {
                    call.close(io.grpc.Status.UNAUTHENTICATED, new io.grpc.Metadata());
                    return new io.grpc.ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
        Server server = ServerBuilder.forPort(0).intercept(security)
                .addService(ServerServiceDefinition.builder("test.Membership")
                        .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                            observer.onNext("authorized");
                            observer.onCompleted();
                        })).build()).build().start();
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false))
                .thenReturn(List.of(instance(server.getPort())));
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = new DiscoveredGrpcChannel(endpoints, "iam-service")) {
            assertThatThrownBy(() -> call(channel)).isInstanceOf(io.grpc.StatusRuntimeException.class)
                    .satisfies(error -> assertThat(io.grpc.Status.fromThrowable(error).getCode())
                            .isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED));
            io.grpc.Metadata headers = new io.grpc.Metadata();
            headers.put(authorization, "Bearer service-token");
            assertThat(call(io.grpc.ClientInterceptors.intercept(channel,
                    io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers)))).isEqualTo("authorized");
        } finally {
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void slowDiscoveryRespectsCallerDeadlineWithoutBlockingChannelClose() throws Exception {
        NamingService naming = mock(NamingService.class);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(naming.selectInstances("iam-service", "DEFAULT_GROUP", true, false)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        try (var endpoints = new NacosServiceEndpoints(naming, "DEFAULT_GROUP");
             var channel = new DiscoveredGrpcChannel(endpoints, "iam-service")) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, METHOD,
                        CallOptions.DEFAULT.withDeadlineAfter(100, TimeUnit.MILLISECONDS), "membership"))
                        .isInstanceOf(io.grpc.StatusRuntimeException.class);
                assertThat(entered.getCount()).isZero();
                channel.close();
            });
        } finally {
            release.countDown();
        }
    }

    static String call(io.grpc.Channel channel) {
        return ClientCalls.blockingUnaryCall(channel, METHOD,
                CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), "membership");
    }

    static Instance instance(int grpcPort) {
        Instance instance = NacosServiceEndpointsTest.instance(1);
        instance.setMetadata(Map.of("grpc.port", Integer.toString(grpcPort)));
        return instance;
    }

    static Server server(String response) throws Exception {
        return ServerBuilder.forPort(0).addService(ServerServiceDefinition.builder("test.Membership")
                .addMethod(METHOD, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(response);
                    observer.onCompleted();
                })).build()).build().start();
    }

    static final class TextMarshaller implements MethodDescriptor.Marshaller<String> {
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
        public String parse(InputStream stream) {
            try { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
            catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        }
    }
}
