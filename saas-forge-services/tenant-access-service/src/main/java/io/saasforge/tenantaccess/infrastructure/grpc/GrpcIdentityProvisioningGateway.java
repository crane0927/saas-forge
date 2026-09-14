package io.saasforge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.iam.identity.v1.EnsureIdentityRequest;
import io.saasforge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saasforge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saasforge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcIdentityProvisioningGateway implements IdentityProvisioningGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcIdentityProvisioningGateway(
            IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public Result ensure(UUID requestId, String email, String displayName) {
        EnsureIdentityRequest.Builder request = EnsureIdentityRequest.newBuilder()
                .setRequestId(requestId.toString())
                .setEmail(email);
        if (displayName != null) {
            request.setDisplayName(displayName);
        }
        try {
            var response = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(accessToken.get())))
                    .ensureIdentity(request.build());
            UUID identityId = UUID.fromString(response.getIdentityId());
            IdentityCredentialDisposition disposition = switch (response.getCredentialStatus()) {
                case SETUP_ALLOWED -> IdentityCredentialDisposition.SETUP_ALLOWED;
                case PASSWORD_READY -> IdentityCredentialDisposition.PASSWORD_READY;
                case RECOVERY_REQUIRED -> IdentityCredentialDisposition.RECOVERY_REQUIRED;
                case IDENTITY_CREDENTIAL_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                        throw new IllegalStateException("IAM 返回未知 Credential 状态");
            };
            return new Result(identityId, disposition);
        } catch (RuntimeException exception) {
            throw new RemoteWorkflowUnavailableException(exception);
        }
    }

    static Metadata metadata(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Service Access Token 不可用");
        }
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return metadata;
    }
}
