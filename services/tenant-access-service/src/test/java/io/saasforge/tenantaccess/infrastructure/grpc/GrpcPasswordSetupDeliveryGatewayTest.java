package io.saasforge.tenantaccess.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupResponse;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.IdentityCredentialRecoveryRequiredException;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcPasswordSetupDeliveryGatewayTest {
    private static final UUID REQUEST_ID = uuidV7(1);
    private static final UUID IDENTITY_ID = uuidV7(2);

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void close() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination();
        }
    }

    @Test
    void mapsCredentialRecoveryToStableApplicationConflict() throws IOException {
        GrpcPasswordSetupDeliveryGateway gateway = gateway(Status.FAILED_PRECONDITION);

        assertThrows(IdentityCredentialRecoveryRequiredException.class,
                () -> gateway.deliver(REQUEST_ID, IDENTITY_ID));
    }

    @Test
    void mapsUncertainRemoteResultToRecoverableFailure() throws IOException {
        GrpcPasswordSetupDeliveryGateway gateway = gateway(Status.UNAVAILABLE);

        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> gateway.deliver(REQUEST_ID, IDENTITY_ID));
    }

    @Test
    void acceptsDeliveredAndPasswordReadyButRejectsUnknownResult() throws Exception {
        gateway(PasswordSetupDeliveryResult.DELIVERED).deliver(REQUEST_ID, IDENTITY_ID);
        close();
        gateway(PasswordSetupDeliveryResult.PASSWORD_READY).deliver(REQUEST_ID, IDENTITY_ID);
        close();

        GrpcPasswordSetupDeliveryGateway unknown = gateway(
                PasswordSetupDeliveryResult.PASSWORD_SETUP_DELIVERY_RESULT_UNSPECIFIED);
        assertThrows(RemoteWorkflowUnavailableException.class,
                () -> unknown.deliver(REQUEST_ID, IDENTITY_ID));
    }

    private GrpcPasswordSetupDeliveryGateway gateway(Status failure) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PasswordSetupServiceGrpc.PasswordSetupServiceImplBase() {
                    @Override
                    public void deliverPasswordSetup(
                            DeliverPasswordSetupRequest request,
                            StreamObserver<DeliverPasswordSetupResponse> responseObserver) {
                        responseObserver.onError(failure.asRuntimeException());
                    }
                })
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        return new GrpcPasswordSetupDeliveryGateway(
                PasswordSetupServiceGrpc.newBlockingStub(channel), () -> "service-token");
    }

    private GrpcPasswordSetupDeliveryGateway gateway(PasswordSetupDeliveryResult result) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PasswordSetupServiceGrpc.PasswordSetupServiceImplBase() {
                    @Override
                    public void deliverPasswordSetup(
                            DeliverPasswordSetupRequest request,
                            StreamObserver<DeliverPasswordSetupResponse> responseObserver) {
                        responseObserver.onNext(DeliverPasswordSetupResponse.newBuilder().setResult(result).build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        return new GrpcPasswordSetupDeliveryGateway(
                PasswordSetupServiceGrpc.newBlockingStub(channel), () -> "service-token");
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }
}
