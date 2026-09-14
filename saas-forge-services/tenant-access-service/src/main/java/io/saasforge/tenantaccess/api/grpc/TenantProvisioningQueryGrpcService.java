package io.saasforge.tenantaccess.api.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityResponse;
import io.saasforge.contracts.tenantaccess.provisioning.v1.TenantProvisioningQueryServiceGrpc;
import io.saasforge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class TenantProvisioningQueryGrpcService
        extends TenantProvisioningQueryServiceGrpc.TenantProvisioningQueryServiceImplBase {
    private final InitialSubscriptionEligibilityService eligibility;

    public TenantProvisioningQueryGrpcService(InitialSubscriptionEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @Override
    public void checkInitialSubscriptionEligibility(
            CheckInitialSubscriptionEligibilityRequest request,
            StreamObserver<CheckInitialSubscriptionEligibilityResponse> responseObserver) {
        try {
            UUID tenantId = canonicalUuidV7(request.getTenantId());
            var outcome = io.saasforge.contracts.tenantaccess.provisioning.v1.InitialSubscriptionEligibility
                    .valueOf(eligibility.check(tenantId).name());
            responseObserver.onNext(CheckInitialSubscriptionEligibilityResponse.newBuilder()
                    .setEligibility(outcome)
                    .build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("tenant_id must be a canonical UUIDv7")
                    .asRuntimeException());
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("UUIDv7 格式不合法");
        }
        return id;
    }
}
