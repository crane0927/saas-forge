package io.saasforge.tenantaccess.api.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsRequest;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsResponse;
import io.saasforge.tenantaccess.application.membership.AccessibleMembershipQuery;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccessibleMembershipGrpcService
        extends AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceImplBase {

    private final AccessibleMembershipQuery memberships;

    public AccessibleMembershipGrpcService(AccessibleMembershipQuery memberships) {
        this.memberships = memberships;
    }

    @Override
    public void listAccessibleMemberships(
            ListAccessibleMembershipsRequest request,
            StreamObserver<ListAccessibleMembershipsResponse> responseObserver) {
        UUID identityId;
        try {
            identityId = canonicalUuidV7(request.getIdentityId());
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("identity_id must be a canonical UUIDv7")
                    .asRuntimeException());
            return;
        }

        ListAccessibleMembershipsResponse.Builder response = ListAccessibleMembershipsResponse.newBuilder();
        memberships.findByIdentityId(identityId).forEach(membership -> response.addMemberships(
                io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership.newBuilder()
                        .setMembershipId(membership.membershipId().toString())
                        .setTenantId(membership.tenantId().toString())
                        .setTenantDisplayName(membership.tenantDisplayName())
                        .build()));
        responseObserver.onNext(response.build());
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
