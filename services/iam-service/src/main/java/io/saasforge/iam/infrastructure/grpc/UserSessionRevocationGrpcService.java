package io.saasforge.iam.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceResponse;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsResponse;
import io.saasforge.contracts.iam.session.v1.SessionRevocationCompleted;
import io.saasforge.contracts.iam.session.v1.SessionRevocationPending;
import io.saasforge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saasforge.iam.application.authentication.RevocationFenceConflictException;
import io.saasforge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saasforge.iam.application.authentication.UserSessionRevocationRecoveryRequiredException;
import io.saasforge.iam.application.authentication.UserSessionRevocationResult;
import io.saasforge.iam.application.authentication.UserSessionRevocationService;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public final class UserSessionRevocationGrpcService
        extends UserSessionRevocationServiceGrpc.UserSessionRevocationServiceImplBase {
    private final UserSessionRevocationService revocations;

    public UserSessionRevocationGrpcService(UserSessionRevocationService revocations) {
        this.revocations = revocations;
    }

    @Override
    public void revokeUserSessions(
            RevokeUserSessionsRequest request, StreamObserver<RevokeUserSessionsResponse> observer) {
        try {
            UserSessionRevocationResult result = revocations.revoke(
                    canonicalUuidV7(request.getRequestId()), target(request));
            var response = RevokeUserSessionsResponse.newBuilder();
            if (result.status() == UserSessionRevocationResult.Status.PENDING) {
                response.setPending(SessionRevocationPending.newBuilder()
                        .setRetryAfterSeconds(Math.toIntExact(result.retryAfterSeconds())));
            } else {
                response.setCompleted(SessionRevocationCompleted.newBuilder()
                        .setRevokedFamilyCount(result.revokedFamilyCount())
                        .setRevokedJtiCount(result.revokedJtiCount()));
            }
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RevocationFenceConflictException | UserSessionRevocationRecoveryRequiredException exception) {
            observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        } catch (RevocationIndexUnavailableException | DataAccessException exception) {
            observer.onError(Status.UNAVAILABLE.asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    @Override
    public void releaseUserSessionFence(
            ReleaseUserSessionFenceRequest request, StreamObserver<ReleaseUserSessionFenceResponse> observer) {
        try {
            revocations.release(canonicalUuidV7(request.getReleaseRequestId()),
                    canonicalUuidV7(request.getRevocationRequestId()), target(request));
            observer.onNext(ReleaseUserSessionFenceResponse.getDefaultInstance());
            observer.onCompleted();
        } catch (IllegalArgumentException exception) {
            observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
        } catch (RevocationFenceConflictException | UserSessionRevocationRecoveryRequiredException exception) {
            observer.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        } catch (RevocationIndexUnavailableException | DataAccessException exception) {
            observer.onError(Status.UNAVAILABLE.asRuntimeException());
        } catch (RuntimeException exception) {
            observer.onError(Status.INTERNAL.asRuntimeException());
        }
    }

    private static RevocationFenceTarget target(RevokeUserSessionsRequest request) {
        return switch (request.getTargetCase()) {
            case MEMBERSHIP_TARGET -> RevocationFenceTarget.membership(
                    canonicalUuidV7(request.getMembershipTarget().getMembershipId()),
                    canonicalUuidV7(request.getMembershipTarget().getTenantId()));
            case TENANT_TARGET -> RevocationFenceTarget.tenant(
                    canonicalUuidV7(request.getTenantTarget().getTenantId()));
            case TARGET_NOT_SET -> throw new IllegalArgumentException("target is required");
        };
    }

    private static RevocationFenceTarget target(ReleaseUserSessionFenceRequest request) {
        return switch (request.getTargetCase()) {
            case MEMBERSHIP_TARGET -> RevocationFenceTarget.membership(
                    canonicalUuidV7(request.getMembershipTarget().getMembershipId()),
                    canonicalUuidV7(request.getMembershipTarget().getTenantId()));
            case TENANT_TARGET -> RevocationFenceTarget.tenant(
                    canonicalUuidV7(request.getTenantTarget().getTenantId()));
            case TARGET_NOT_SET -> throw new IllegalArgumentException("target is required");
        };
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new IllegalArgumentException("IDs must be canonical UUIDv7 values");
        }
        return id;
    }
}
