package io.saasforge.tenantaccess.infrastructure.grpc;

import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcPasswordSetupDeliveryGateway implements PasswordSetupDeliveryGateway {
    private final PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcPasswordSetupDeliveryGateway(
            PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public void deliver(UUID requestId, UUID identityId) {
        try {
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                            GrpcIdentityProvisioningGateway.metadata(accessToken.get())))
                    .deliverPasswordSetup(DeliverPasswordSetupRequest.newBuilder()
                            .setRequestId(requestId.toString())
                            .setIdentityId(identityId.toString())
                            .build());
            if (response.getResult() != PasswordSetupDeliveryResult.DELIVERED
                    && response.getResult() != PasswordSetupDeliveryResult.PASSWORD_READY) {
                throw new IllegalStateException("IAM 返回未知 Password Setup 投递结果");
            }
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
    }
}
