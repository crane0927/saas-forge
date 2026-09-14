package io.saasforge.iam.infrastructure.grpc;

import io.grpc.StatusRuntimeException;
import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsRequest;
import io.saasforge.iam.application.authentication.AccessibleMembership;
import io.saasforge.iam.application.authentication.AccessibleMemberships;
import io.saasforge.iam.application.authentication.TenantAccessUnavailableException;
import io.saasforge.iam.application.authentication.TenantBrandProfileSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class GrpcAccessibleMemberships implements AccessibleMemberships {
    private final AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceBlockingStub client;

    public GrpcAccessibleMemberships(
            AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceBlockingStub client) {
        this.client = client;
    }

    @Override
    public List<AccessibleMembership> findByIdentityId(UUID identityId) {
        try {
            return client.listAccessibleMemberships(ListAccessibleMembershipsRequest.newBuilder()
                            .setIdentityId(identityId.toString())
                            .build())
                    .getMembershipsList().stream()
                    .map(value -> new AccessibleMembership(
                            canonicalUuidV7(value.getMembershipId()),
                            canonicalUuidV7(value.getTenantId()),
                            value.getTenantDisplayName(),
                            value.hasBrandProfile()
                                    ? new TenantBrandProfileSnapshot(
                                            value.getBrandProfile().getDisplayName(),
                                            value.getBrandProfile().hasLogoUrl()
                                                    ? value.getBrandProfile().getLogoUrl() : null,
                                            value.getBrandProfile().hasFaviconUrl()
                                                    ? value.getBrandProfile().getFaviconUrl() : null,
                                            value.getBrandProfile().getPrimaryColor(),
                                            value.getBrandProfile().getAccentColor())
                                    : null))
                    .sorted(Comparator.comparing(AccessibleMembership::tenantDisplayName)
                            .thenComparing(membership -> membership.membershipId().toString()))
                    .toList();
        } catch (StatusRuntimeException | IllegalArgumentException exception) {
            throw new TenantAccessUnavailableException(exception);
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("Tenant Access 返回了非规范 UUIDv7");
        }
        return id;
    }
}
