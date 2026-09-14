package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleResponse;
import io.saasforge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saasforge.iam.application.authorization.PlatformRoleAuthorizationService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class PlatformAuthorizationGrpcService
        extends PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceImplBase {
    private final PlatformRoleAuthorizationService authorization;

    public PlatformAuthorizationGrpcService(PlatformRoleAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public void checkPlatformRole(
            CheckPlatformRoleRequest request,
            StreamObserver<CheckPlatformRoleResponse> responseObserver) {
        try {
            UUID identityId = canonicalUuidV7(request.getIdentityId());
            boolean allowed = authorization.isAllowed(identityId, request.getRoleKey());
            responseObserver.onNext(CheckPlatformRoleResponse.newBuilder()
                    .setAllowed(allowed)
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("identity_id must be a canonical UUIDv7");
        }
        return id;
    }
}
