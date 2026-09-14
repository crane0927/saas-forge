package io.saas.forge.iam.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupResponse;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saas.forge.iam.application.authentication.IdentityCredentialRecoveryRequiredException;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryRequestConflictException;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryUnavailableException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class PasswordSetupDeliveryGrpcService
        extends PasswordSetupServiceGrpc.PasswordSetupServiceImplBase {
    private final PasswordSetupDeliveryService deliveries;

    private final io.saas.forge.iam.application.authentication.PasswordSetupNotificationQueryService queries;

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordSetupDeliveryGrpcService(PasswordSetupDeliveryService deliveries,
            io.saas.forge.iam.application.authentication.PasswordSetupNotificationQueryService queries) {
        this.queries = queries;
        this.deliveries = deliveries;
    }

    public PasswordSetupDeliveryGrpcService(PasswordSetupDeliveryService deliveries) {
        this(deliveries, null);
    }

    @Override
    public void getPasswordSetupNotification(
            io.saas.forge.contracts.iam.passwordsetup.v1.GetPasswordSetupNotificationRequest request,
            StreamObserver<io.saas.forge.contracts.iam.passwordsetup.v1.GetPasswordSetupNotificationResponse> observer) {
        UUID caller = PasswordSetupDeliveryServerInterceptor.callerClientId();
        if (caller == null) {
            observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        try {
            var state = queries.get(caller, canonicalUuidV7(request.getRequestId()), canonicalUuidV7(request.getIdentityId()));
            var wire = io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupNotificationState.valueOf(
                    state == io.saas.forge.iam.application.authentication.PasswordSetupNotificationQueryService.State.PASSWORD_READY
                            ? "PASSWORD_ALREADY_READY" : state.name());
            observer.onNext(io.saas.forge.contracts.iam.passwordsetup.v1.GetPasswordSetupNotificationResponse.newBuilder()
                    .setState(wire).build());
            observer.onCompleted();
        } catch (PasswordSetupDeliveryRequestConflictException exception) {
            observer.onError(Status.ALREADY_EXISTS.asRuntimeException());
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.UNAVAILABLE.asRuntimeException());
        }
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
                        case DELIVERED -> io.saas.forge.contracts.iam.passwordsetup.v1
                                .PasswordSetupDeliveryResult.DELIVERED;
                        case PASSWORD_READY -> io.saas.forge.contracts.iam.passwordsetup.v1
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
