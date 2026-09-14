package io.saasforge.tenantaccess.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.iam.session.v1.RecoverUserSessionRevocationRequest;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saasforge.contracts.iam.session.v1.TenantTarget;
import io.saasforge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationGateway;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationUnavailableException;
import io.saasforge.tenantaccess.application.tenant.SessionRevocationRejectedException;
import java.util.UUID;
import java.util.function.Supplier;

public final class GrpcSessionRevocationGateway implements SessionRevocationGateway {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private final UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client;
    private final Supplier<String> accessToken;

    public GrpcSessionRevocationGateway(
            UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub client,
            Supplier<String> accessToken) {
        this.client = client;
        this.accessToken = accessToken;
    }

    @Override
    public Result revoke(UUID revocationRequestId, UUID tenantId) {
        try {
            var response = authorized().revokeUserSessions(RevokeUserSessionsRequest.newBuilder()
                    .setRequestId(revocationRequestId.toString())
                    .setTenantTarget(target(tenantId))
                    .build());
            return switch (response.getOutcomeCase()) {
                case PENDING -> Result.pending(response.getPending().getRetryAfterSeconds());
                case COMPLETED -> Result.completed(
                        response.getCompleted().getRevokedFamilyCount(),
                        response.getCompleted().getRevokedJtiCount());
                case OUTCOME_NOT_SET -> throw new IllegalStateException("IAM 未返回撤销结果");
            };
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public void recover(UUID revocationRequestId, UUID tenantId) {
        try {
            authorized().recoverUserSessionRevocation(RecoverUserSessionRevocationRequest.newBuilder()
                    .setRevocationRequestId(revocationRequestId.toString())
                    .setTenantTarget(target(tenantId))
                    .build());
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    @Override
    public void release(UUID releaseRequestId, UUID revocationRequestId, UUID tenantId) {
        try {
            authorized().releaseUserSessionFence(ReleaseUserSessionFenceRequest.newBuilder()
                    .setReleaseRequestId(releaseRequestId.toString())
                    .setRevocationRequestId(revocationRequestId.toString())
                    .setTenantTarget(target(tenantId))
                    .build());
        } catch (StatusRuntimeException exception) {
            throw map(exception);
        }
    }

    private UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub authorized() {
        String token = accessToken.get();
        if (token == null || token.isBlank()) throw new IllegalStateException("Service Access Token 不可用");
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static TenantTarget target(UUID tenantId) {
        return TenantTarget.newBuilder().setTenantId(tenantId.toString()).build();
    }

    private static RuntimeException map(StatusRuntimeException exception) {
        Status.Code code = exception.getStatus().getCode();
        if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
            return new SessionRevocationUnavailableException(exception);
        }
        if (code == Status.Code.FAILED_PRECONDITION) {
            return new SessionRevocationRejectedException();
        }
        return new IllegalStateException("IAM User Session Revocation 调用失败: " + code);
    }
}
