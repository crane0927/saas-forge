package io.saasforge.tenantaccess.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityRequest;
import io.saasforge.contracts.tenantaccess.provisioning.v1.CheckInitialSubscriptionEligibilityResponse;
import io.saasforge.tenantaccess.application.tenant.InitialSubscriptionEligibility;
import io.saasforge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TenantProvisioningQueryGrpcServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019535d9-0000-7000-8000-000000000031");

    @Test
    void mapsEveryAuthoritativeEligibilityOutcome() {
        InitialSubscriptionEligibilityService eligibility = Mockito.mock(InitialSubscriptionEligibilityService.class);
        var service = new TenantProvisioningQueryGrpcService(eligibility);

        for (InitialSubscriptionEligibility outcome : InitialSubscriptionEligibility.values()) {
            when(eligibility.check(TENANT_ID)).thenReturn(outcome);
            CapturingObserver observer = new CapturingObserver();
            service.checkInitialSubscriptionEligibility(request(TENANT_ID.toString()), observer);

            assertEquals(outcome.name(), observer.response.getEligibility().name());
            assertEquals(1, observer.completions);
            assertNull(observer.error);
        }
    }

    @Test
    void rejectsMalformedNonV7AndNonCanonicalTenantIds() {
        var service = new TenantProvisioningQueryGrpcService(
                Mockito.mock(InitialSubscriptionEligibilityService.class));
        for (String tenantId : new String[] {"not-a-uuid", UUID.randomUUID().toString(),
                TENANT_ID.toString().toUpperCase()}) {
            CapturingObserver observer = new CapturingObserver();
            service.checkInitialSubscriptionEligibility(request(tenantId), observer);

            StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, observer.error);
            assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
            assertNull(observer.response);
            assertEquals(0, observer.completions);
        }
    }

    private static CheckInitialSubscriptionEligibilityRequest request(String tenantId) {
        return CheckInitialSubscriptionEligibilityRequest.newBuilder().setTenantId(tenantId).build();
    }

    private static final class CapturingObserver
            implements StreamObserver<CheckInitialSubscriptionEligibilityResponse> {
        private CheckInitialSubscriptionEligibilityResponse response;
        private Throwable error;
        private int completions;

        @Override public void onNext(CheckInitialSubscriptionEligibilityResponse value) { response = value; }
        @Override public void onError(Throwable value) { error = value; }
        @Override public void onCompleted() { completions++; }
    }
}
