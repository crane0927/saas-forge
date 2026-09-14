package io.saasforge.tenantaccess.api.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipNotUsable;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saasforge.contracts.tenantaccess.membership.v1.ValidateMembershipResponse;
import io.saasforge.tenantaccess.application.membership.MembershipValidationQuery;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class MembershipValidationGrpcService
        extends MembershipValidationServiceGrpc.MembershipValidationServiceImplBase {
    private final MembershipValidationQuery memberships;

    public MembershipValidationGrpcService(MembershipValidationQuery memberships) {
        this.memberships = memberships;
    }

    @Override
    public void validateMembership(
            ValidateMembershipRequest request,
            StreamObserver<ValidateMembershipResponse> responseObserver) {
        UUID identityId;
        UUID membershipId;
        try {
            identityId = canonicalUuidV7(request.getIdentityId());
            membershipId = canonicalUuidV7(request.getMembershipId());
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("identity_id and membership_id must be canonical UUIDv7 values")
                    .asRuntimeException());
            return;
        }

        ValidateMembershipResponse response = memberships.findUsable(identityId, membershipId)
                .map(validated -> ValidateMembershipResponse.newBuilder()
                        .setValidatedMembership(
                                io.saasforge.contracts.tenantaccess.membership.v1.ValidatedMembership.newBuilder()
                                        .setMembershipId(validated.membershipId().toString())
                                        .setTenantId(validated.tenantId().toString()))
                        .build())
                .orElseGet(() -> ValidateMembershipResponse.newBuilder()
                        .setMembershipNotUsable(MembershipNotUsable.getDefaultInstance())
                        .build());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("UUIDv7 格式不合法");
        }
        return id;
    }
}
