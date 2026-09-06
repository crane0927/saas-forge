package io.saasforge.entitlement.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleResponse;
import io.saasforge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcPlatformRoleCheckerTest {
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private static final Context.Key<String> AUTHORIZATION = Context.key("authorization");

    private final AtomicReference<CheckPlatformRoleRequest> request = new AtomicReference<>();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceImplBase service =
                new PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceImplBase() {
                    @Override
                    public void checkPlatformRole(
                            CheckPlatformRoleRequest value,
                            StreamObserver<CheckPlatformRoleResponse> responseObserver) {
                        request.set(value);
                        responseObserver.onNext(CheckPlatformRoleResponse.newBuilder()
                                .setAllowed("Bearer service-token".equals(AUTHORIZATION.get()))
                                .build());
                        responseObserver.onCompleted();
                    }
                };
        ServerInterceptor authorization = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                String value = headers.get(Metadata.Key.of(
                        "authorization", Metadata.ASCII_STRING_MARSHALLER));
                return Contexts.interceptCall(
                        Context.current().withValue(AUTHORIZATION, value), call, headers, next);
            }
        };
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, authorization))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void callsIamWithExactRoleAndCurrentServiceAccessToken() {
        GrpcPlatformRoleChecker checker = new GrpcPlatformRoleChecker(
                PlatformAuthorizationServiceGrpc.newBlockingStub(channel), () -> "service-token");

        assertTrue(checker.isAllowed(IDENTITY_ID, PLATFORM_ADMIN));
        assertEquals(IDENTITY_ID.toString(), request.get().getIdentityId());
        assertEquals(PLATFORM_ADMIN, request.get().getRoleKey());
    }

    @Test
    void rejectsInvalidInputAndFailsClosedWhenTokenOrIamIsUnavailable() throws Exception {
        var stub = PlatformAuthorizationServiceGrpc.newBlockingStub(channel);
        GrpcPlatformRoleChecker checker = new GrpcPlatformRoleChecker(stub, () -> "service-token");

        assertThrows(IllegalArgumentException.class, () -> checker.isAllowed(null, PLATFORM_ADMIN));
        assertThrows(IllegalArgumentException.class, () -> checker.isAllowed(IDENTITY_ID, " "));
        assertThrows(IllegalStateException.class,
                () -> new GrpcPlatformRoleChecker(stub, () -> " ").isAllowed(IDENTITY_ID, PLATFORM_ADMIN));

        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThrows(IllegalStateException.class, () -> checker.isAllowed(IDENTITY_ID, PLATFORM_ADMIN));
    }
}
