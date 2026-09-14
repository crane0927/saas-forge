package io.saasforge.contracts.acceptance;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saasforge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Issue #74 Compose 验收使用的真实 IAM Service Token 接收端探针。 */
public final class PlatformAuthorizationGrpcProbe {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private PlatformAuthorizationGrpcProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 4 || !("allowed".equals(args[3])
                || "rejected".equals(args[3])
                || "unavailable".equals(args[3]))) {
            throw new IllegalArgumentException(
                    "Usage: PlatformAuthorizationGrpcProbe "
                            + "<host> <port> <identity-id> <allowed|rejected|unavailable>");
        }
        String token = System.getenv("SERVICE_ACCESS_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("SERVICE_ACCESS_TOKEN is required");
        }
        UUID identityId = UUID.fromString(args[2]);
        if (identityId.version() != 7 || !identityId.toString().equals(args[2])) {
            throw new IllegalArgumentException("identity-id must be a canonical UUIDv7");
        }

        ManagedChannel channel = ManagedChannelBuilder.forAddress(args[0], Integer.parseInt(args[1]))
                .usePlaintext()
                .build();
        try {
            boolean allowed = call(channel, token, identityId);
            if (!"allowed".equals(args[3]) || !allowed) {
                throw new IllegalStateException("IAM receiver unexpectedly returned a decision");
            }
            System.out.println("IAM Service Token receiver accepted the request");
        } catch (StatusRuntimeException exception) {
            Status.Code code = exception.getStatus().getCode();
            boolean explicitlyRejected = "rejected".equals(args[3]) && code == Status.Code.UNAUTHENTICATED;
            boolean unavailableFailClosed = "unavailable".equals(args[3])
                    && (code == Status.Code.UNAUTHENTICATED || code == Status.Code.DEADLINE_EXCEEDED);
            if (!explicitlyRejected && !unavailableFailClosed) {
                throw exception;
            }
            System.out.println("IAM Service Token receiver rejected the request fail-closed");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static boolean call(ManagedChannel channel, String token, UUID identityId) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, "Bearer " + token);
        return PlatformAuthorizationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                        .setIdentityId(identityId.toString())
                        .setRoleKey("PLATFORM_ADMIN")
                        .build())
                .getAllowed();
    }
}
