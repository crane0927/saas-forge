package io.saasforge.tenantaccess.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saasforge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saasforge.tenantaccess.application.administrator.QuotaUnavailableException;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcInitializationQuotaGateway implements InitializationQuotaGateway {
    private final QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcInitializationQuotaGateway(
            QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public void consume(UUID tenantId, UUID operationId) {
        try {
            client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                            GrpcIdentityProvisioningGateway.metadata(accessToken.get())))
                    .consume(QuotaCommandRequest.newBuilder()
                            .setTenantId(tenantId.toString())
                            .setQuotaCode("max_users")
                            .setAmount(1)
                            .setOperationId(operationId.toString())
                            .setPurpose(QuotaPurpose.TENANT_ADMIN_INITIALIZATION)
                            .build());
        } catch (StatusRuntimeException exception) {
            String code = exception.getStatus().getDescription();
            if ((exception.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED
                    && "QUOTA_EXCEEDED".equals(code))
                    || (exception.getStatus().getCode() == Status.Code.FAILED_PRECONDITION
                    && "SUBSCRIPTION_REQUIRED".equals(code))) {
                throw new QuotaUnavailableException(code, exception);
            }
            throw new RemoteWorkflowUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
    }
}
