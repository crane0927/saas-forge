package io.saasforge.entitlement.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saasforge.entitlement.application.subscription.TenantEligibilityUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

/** 通过版本化 gRPC 契约实时读取 Tenant Access 权威资格，不缓存或复制 Tenant 状态。 */
public final class GrpcTenantEligibilityGateway implements TenantEligibilityGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub client;
    private final Supplier<String> serviceAccessToken;

    public GrpcTenantEligibilityGateway(
            TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceBlockingStub client,
            Supplier<String> serviceAccessToken) {
        this.client = client;
        this.serviceAccessToken = serviceAccessToken;
    }

    @Override
    public Outcome checkInitialSubscription(UUID tenantId) {
        String token;
        try {
            token = serviceAccessToken.get();
        } catch (RuntimeException exception) {
            throw new TenantEligibilityUnavailableException(exception);
        }
        if (token == null || token.isBlank()) {
            throw new TenantEligibilityUnavailableException(
                    new IllegalStateException("Entitlement Service Access Token 不可用"));
        }
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        try {
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .checkInitialSubscriptionEligibility(
                            CheckInitialSubscriptionEligibilityRequest.newBuilder()
                                    .setTenantId(tenantId.toString())
                                    .build());
            return switch (response.getEligibility()) {
                case PENDING_ELIGIBLE -> Outcome.PENDING_ELIGIBLE;
                case NOT_FOUND -> Outcome.NOT_FOUND;
                case INVALID_STATE -> Outcome.INVALID_STATE;
                case EXPIRY_REACHED -> Outcome.EXPIRY_REACHED;
                case INITIAL_SUBSCRIPTION_ELIGIBILITY_UNSPECIFIED, UNRECOGNIZED ->
                        throw new TenantEligibilityUnavailableException(
                                new IllegalStateException("Tenant Access 返回未知资格"));
            };
        } catch (TenantEligibilityUnavailableException exception) {
            throw exception;
        } catch (StatusRuntimeException exception) {
            throw new TenantEligibilityUnavailableException(exception);
        }
    }
}
