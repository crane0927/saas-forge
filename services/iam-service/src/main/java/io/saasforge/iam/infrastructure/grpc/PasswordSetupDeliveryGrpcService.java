package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupResponse;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.iam.application.authentication.IdentityCredentialRecoveryRequiredException;
import io.saasforge.iam.application.authentication.PasswordSetupDeliveryRequestConflictException;
import io.saasforge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saasforge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class PasswordSetupDeliveryGrpcService
        extends PasswordSetupServiceGrpc.PasswordSetupServiceImplBase {
    private final PasswordSetupDeliveryService deliveries;

    public PasswordSetupDeliveryGrpcService(PasswordSetupDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public void deliverPasswordSetup(
            DeliverPasswordSetupRequest request,
            StreamObserver<DeliverPasswordSetupResponse> responseObserver) {
        UUID callerClientId = PasswordSetupDeliveryServerInterceptor.callerClientId();
        if (callerClientId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        try {
            var result = deliveries.deliver(
                    callerClientId,
                    canonicalUuidV7(request.getRequestId()),
                    canonicalUuidV7(request.getIdentityId()),
                    null);
            responseObserver.onNext(DeliverPasswordSetupResponse.newBuilder()
                    .setResult(switch (result) {
                        case DELIVERED -> io.saasforge.contracts.iam.passwordsetup.v1
                                .PasswordSetupDeliveryResult.DELIVERED;
                        case PASSWORD_READY -> io.saasforge.contracts.iam.passwordsetup.v1
                                .PasswordSetupDeliveryResult.PASSWORD_READY;
                    })
                    .build());
            responseObserver.onCompleted();
        } catch (PasswordSetupDeliveryRequestConflictException exception) {
            responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
        } catch (IdentityCredentialRecoveryRequiredException exception) {
            responseObserver.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        } catch (PasswordSetupDeliveryUnavailableException exception) {
            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("request_id and identity_id must be canonical UUIDv7 values");
        }
        return id;
    }
}
