package io.saasforge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.session.v1.RecoverUserSessionRevocationRequest;
import io.saasforge.contracts.iam.session.v1.RecoverUserSessionRevocationResponse;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceResponse;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsResponse;
import io.saasforge.contracts.iam.session.v1.SessionRevocationCompleted;
import io.saasforge.contracts.iam.session.v1.SessionRevocationPending;
import io.saasforge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationUnavailableException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcSessionRevocationGatewayTest {
    private static final UUID REVOCATION_REQUEST_ID = uuidV7(1);
    private static final UUID SECOND_REVOCATION_REQUEST_ID = uuidV7(2);
    private static final UUID RELEASE_REQUEST_ID = uuidV7(3);
    private static final UUID TENANT_ID = uuidV7(4);

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mapsOutcomesAndSendsExactIdsTargetAndBearerToken() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<RecoverUserSessionRevocationRequest> recovered = new AtomicReference<>();
        AtomicReference<ReleaseUserSessionFenceRequest> released = new AtomicReference<>();
        var service = new UserSessionRevocationServiceGrpc.UserSessionRevocationServiceImplBase() {
            @Override
            public void revokeUserSessions(
                    RevokeUserSessionsRequest request,
                    StreamObserver<RevokeUserSessionsResponse> observer) {
                RevokeUserSessionsResponse.Builder response = RevokeUserSessionsResponse.newBuilder();
                if (request.getRequestId().equals(REVOCATION_REQUEST_ID.toString())) {
                    response.setPending(SessionRevocationPending.newBuilder().setRetryAfterSeconds(4));
                } else {
                    response.setCompleted(SessionRevocationCompleted.newBuilder()
                            .setRevokedFamilyCount(3)
                            .setRevokedJtiCount(7));
                }
                assertEquals(TENANT_ID.toString(), request.getTenantTarget().getTenantId());
                observer.onNext(response.build());
                observer.onCompleted();
            }

            @Override
            public void recoverUserSessionRevocation(
                    RecoverUserSessionRevocationRequest request,
                    StreamObserver<RecoverUserSessionRevocationResponse> observer) {
                recovered.set(request);
                observer.onNext(RecoverUserSessionRevocationResponse.getDefaultInstance());
                observer.onCompleted();
            }

            @Override
            public void releaseUserSessionFence(
                    ReleaseUserSessionFenceRequest request,
                    StreamObserver<ReleaseUserSessionFenceResponse> observer) {
                released.set(request);
                observer.onNext(ReleaseUserSessionFenceResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
        start(service, authorization);
        GrpcSessionRevocationGateway gateway = gateway(() -> "service-token");

        SessionRevocationGateway.Result pending = gateway.revoke(REVOCATION_REQUEST_ID, TENANT_ID);
        SessionRevocationGateway.Result completed = gateway.revoke(SECOND_REVOCATION_REQUEST_ID, TENANT_ID);
        gateway.recover(REVOCATION_REQUEST_ID, TENANT_ID);
        gateway.release(RELEASE_REQUEST_ID, REVOCATION_REQUEST_ID, TENANT_ID);

        assertEquals(SessionRevocationGateway.Result.pending(4), pending);
        assertEquals(SessionRevocationGateway.Result.completed(3, 7), completed);
        assertEquals("Bearer service-token", authorization.get());
        assertEquals(REVOCATION_REQUEST_ID.toString(), recovered.get().getRevocationRequestId());
        assertEquals(TENANT_ID.toString(), recovered.get().getTenantTarget().getTenantId());
        assertEquals(RELEASE_REQUEST_ID.toString(), released.get().getReleaseRequestId());
        assertEquals(REVOCATION_REQUEST_ID.toString(), released.get().getRevocationRequestId());
        assertEquals(TENANT_ID.toString(), released.get().getTenantTarget().getTenantId());
    }

    @Test
    void failsClosedWhenIamOmitsTheBusinessOutcome() throws IOException {
        start(revocationService(null, null), new AtomicReference<>());

        assertThrows(IllegalStateException.class,
                () -> gateway(() -> "token").revoke(REVOCATION_REQUEST_ID, TENANT_ID));
    }

    @Test
    void mapsGrpcStatusByRecoverabilityAcrossAllOperations() throws Exception {
        start(revocationService(Status.UNAVAILABLE, null), new AtomicReference<>());
        assertThrows(SessionRevocationUnavailableException.class,
                () -> gateway(() -> "token").revoke(REVOCATION_REQUEST_ID, TENANT_ID));
        close();

        start(revocationService(Status.DEADLINE_EXCEEDED, null), new AtomicReference<>());
        assertThrows(SessionRevocationUnavailableException.class,
                () -> gateway(() -> "token").recover(REVOCATION_REQUEST_ID, TENANT_ID));
        close();

        start(revocationService(Status.FAILED_PRECONDITION, null), new AtomicReference<>());
        assertThrows(SessionRevocationRejectedException.class,
                () -> gateway(() -> "token").release(
                        RELEASE_REQUEST_ID, REVOCATION_REQUEST_ID, TENANT_ID));
        close();

        start(revocationService(Status.INVALID_ARGUMENT, null), new AtomicReference<>());
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> gateway(() -> "token").revoke(REVOCATION_REQUEST_ID, TENANT_ID));
        assertEquals("IAM User Session Revocation 调用失败: INVALID_ARGUMENT", invalid.getMessage());
    }

    @Test
    void rejectsMissingServiceAccessTokenBeforeCallingIam() throws IOException {
        start(revocationService(null, SessionRevocationGateway.Result.completed(0, 0)),
                new AtomicReference<>());
        assertThrows(IllegalStateException.class,
                () -> gateway(() -> null).revoke(REVOCATION_REQUEST_ID, TENANT_ID));
        assertThrows(IllegalStateException.class,
                () -> gateway(() -> " ").recover(REVOCATION_REQUEST_ID, TENANT_ID));
    }

    private GrpcSessionRevocationGateway gateway(java.util.function.Supplier<String> token) {
        return new GrpcSessionRevocationGateway(
                UserSessionRevocationServiceGrpc.newBlockingStub(channel), token);
    }

    private void start(
            UserSessionRevocationServiceGrpc.UserSessionRevocationServiceImplBase service,
            AtomicReference<String> authorization) throws IOException {
        Metadata.Key<String> authorizationKey =
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        authorization.set(headers.get(authorizationKey));
                        return next.startCall(call, headers);
                    }
                }))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private static UserSessionRevocationServiceGrpc.UserSessionRevocationServiceImplBase revocationService(
            Status failure, SessionRevocationGateway.Result result) {
        return new UserSessionRevocationServiceGrpc.UserSessionRevocationServiceImplBase() {
            @Override
            public void revokeUserSessions(
                    RevokeUserSessionsRequest request,
                    StreamObserver<RevokeUserSessionsResponse> observer) {
                if (failure != null) {
                    observer.onError(failure.asRuntimeException());
                    return;
                }
                RevokeUserSessionsResponse.Builder response = RevokeUserSessionsResponse.newBuilder();
                if (result != null && result.status() == SessionRevocationGateway.Result.Status.PENDING) {
                    response.setPending(SessionRevocationPending.newBuilder()
                            .setRetryAfterSeconds(Math.toIntExact(result.retryAfterSeconds())));
                } else if (result != null) {
                    response.setCompleted(SessionRevocationCompleted.newBuilder()
                            .setRevokedFamilyCount(result.revokedFamilyCount())
                            .setRevokedJtiCount(result.revokedJtiCount()));
                }
                observer.onNext(response.build());
                observer.onCompleted();
            }

            @Override
            public void recoverUserSessionRevocation(
                    RecoverUserSessionRevocationRequest request,
                    StreamObserver<RecoverUserSessionRevocationResponse> observer) {
                if (failure != null) {
                    observer.onError(failure.asRuntimeException());
                    return;
                }
                observer.onNext(RecoverUserSessionRevocationResponse.getDefaultInstance());
                observer.onCompleted();
            }

            @Override
            public void releaseUserSessionFence(
                    ReleaseUserSessionFenceRequest request,
                    StreamObserver<ReleaseUserSessionFenceResponse> observer) {
                if (failure != null) {
                    observer.onError(failure.asRuntimeException());
                    return;
                }
                observer.onNext(ReleaseUserSessionFenceResponse.getDefaultInstance());
                observer.onCompleted();
            }
        };
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
