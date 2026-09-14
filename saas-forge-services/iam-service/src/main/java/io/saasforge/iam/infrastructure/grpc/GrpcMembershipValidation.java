package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saasforge.iam.application.authentication.MembershipValidation;
import io.saasforge.iam.application.authentication.TenantAccessUnavailableException;
import io.saasforge.iam.application.authentication.ValidatedMembership;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcMembershipValidation implements MembershipValidation {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final MembershipValidationServiceGrpc.MembershipValidationServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcMembershipValidation(
            MembershipValidationServiceGrpc.MembershipValidationServiceBlockingStub client,
            Supplier<String> accessToken) {
        if (client == null || accessToken == null) {
            throw new IllegalArgumentException("Membership Validation gRPC 配置不完整");
        }
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public Optional<ValidatedMembership> validate(UUID identityId, UUID membershipId) {
        if (identityId == null || identityId.version() != 7 || membershipId == null || membershipId.version() != 7) {
            throw new IllegalArgumentException("Membership Validation 只接受 UUIDv7");
        }
        try {
            String token = accessToken.get();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("IAM Service Access Token 不可用");
            }
            Metadata metadata = new Metadata();
            metadata.put(AUTHORIZATION, "Bearer " + token);
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .validateMembership(ValidateMembershipRequest.newBuilder()
                            .setIdentityId(identityId.toString())
                            .setMembershipId(membershipId.toString())
                            .build());
            return switch (response.getOutcomeCase()) {
                case MEMBERSHIP_NOT_USABLE -> Optional.empty();
                case VALIDATED_MEMBERSHIP -> {
                    UUID returnedMembershipId = canonicalUuidV7(
                            response.getValidatedMembership().getMembershipId());
                    UUID tenantId = canonicalUuidV7(response.getValidatedMembership().getTenantId());
                    if (!membershipId.equals(returnedMembershipId)) {
                        throw new IllegalStateException("Tenant Access 返回了不匹配的 Membership");
                    }
                    yield Optional.of(new ValidatedMembership(returnedMembershipId, tenantId));
                }
                case OUTCOME_NOT_SET -> throw new IllegalStateException("Tenant Access 返回了非法 Membership 判定");
            };
        } catch (StatusRuntimeException | IllegalArgumentException | IllegalStateException exception) {
            throw new TenantAccessUnavailableException(exception);
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalStateException("Tenant Access 返回了非规范 UUIDv7");
        }
        return id;
    }
}
