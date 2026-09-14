package io.saas.forge.tenantaccess.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saas.forge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saas.forge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saas.forge.tenantaccess.application.administrator.PasswordSetupDeliveryGateway;
import io.saas.forge.tenantaccess.application.administrator.IdentityCredentialRecoveryRequiredException;
import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
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
    public NotificationState notification(UUID requestId, UUID identityId) {
        try {
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(
                            GrpcIdentityProvisioningGateway.metadata(accessToken.get())))
                    .getPasswordSetupNotification(io.saas.forge.contracts.iam.passwordsetup.v1.GetPasswordSetupNotificationRequest.newBuilder()
                            .setRequestId(requestId.toString()).setIdentityId(identityId.toString()).build());
            return switch (response.getState()) {
                case NOT_REQUESTED -> NotificationState.NOT_REQUESTED;
                case PENDING -> NotificationState.PENDING;
                case MAIL_SERVICE_ACCEPTED -> NotificationState.MAIL_SERVICE_ACCEPTED;
                case PASSWORD_ALREADY_READY -> NotificationState.PASSWORD_READY;
                case RECOVERY_REQUIRED -> NotificationState.RECOVERY_REQUIRED;
                default -> throw new IllegalStateException("Unknown IAM notification state");
            };
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
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
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                throw new IdentityCredentialRecoveryRequiredException();
            }
            throw new RemoteWorkflowUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
    }
}
