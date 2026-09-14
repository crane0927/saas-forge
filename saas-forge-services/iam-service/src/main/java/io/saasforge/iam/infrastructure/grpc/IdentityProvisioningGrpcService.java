package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.identity.v1.EnsureIdentityRequest;
import io.saasforge.contracts.iam.identity.v1.EnsureIdentityResponse;
import io.saasforge.contracts.iam.identity.v1.IdentityCredentialStatus;
import io.saasforge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saasforge.iam.application.identity.EnsureIdentityRequestConflictException;
import io.saasforge.iam.application.identity.EnsureIdentityResult;
import io.saasforge.iam.application.identity.EnsureIdentityService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class IdentityProvisioningGrpcService
        extends IdentityProvisioningServiceGrpc.IdentityProvisioningServiceImplBase {
    private final EnsureIdentityService identities;

    public IdentityProvisioningGrpcService(EnsureIdentityService identities) {
        this.identities = identities;
    }

    @Override
    public void ensureIdentity(
            EnsureIdentityRequest request,
            StreamObserver<EnsureIdentityResponse> responseObserver) {
        UUID callerClientId = IdentityProvisioningServerInterceptor.callerClientId();
        if (callerClientId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        try {
            EnsureIdentityResult result = identities.ensure(
                    callerClientId,
                    canonicalUuidV7(request.getRequestId()),
                    request.getEmail(),
                    request.hasDisplayName() ? request.getDisplayName() : null);
            responseObserver.onNext(EnsureIdentityResponse.newBuilder()
                    .setIdentityId(result.identityId().toString())
                    .setCredentialStatus(credentialStatus(result.credentialStatus()))
                    .build());
            responseObserver.onCompleted();
        } catch (EnsureIdentityRequestConflictException exception) {
            responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("request_id must be a canonical UUIDv7");
        }
        return id;
    }

    private static IdentityCredentialStatus credentialStatus(
            io.saasforge.iam.domain.identity.IdentityCredentialStatus status) {
        return switch (status) {
            case SETUP_ALLOWED -> IdentityCredentialStatus.SETUP_ALLOWED;
            case PASSWORD_READY -> IdentityCredentialStatus.PASSWORD_READY;
            case RECOVERY_REQUIRED -> IdentityCredentialStatus.RECOVERY_REQUIRED;
        };
    }
}
