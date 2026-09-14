package io.saasforge.contracts.acceptance;

import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.contracts.entitlement.quota.v1.QuotaPurpose;
import java.util.concurrent.TimeUnit;

/** 隔离生命周期验收通过正式 gRPC 真实占满正额度，再释放至零；不直接改用量表。 */
public final class QuotaCommandGrpcProbe {
    private QuotaCommandGrpcProbe() { }

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 4 || !(args[3].equals("consume") || args[3].equals("release"))) {
            throw new IllegalArgumentException("Usage: <port> <tenantId> <operationId> <consume|release>");
        }
        var channel = ManagedChannelBuilder.forAddress("127.0.0.1", Integer.parseInt(args[0])).usePlaintext().build();
        try {
            var metadata = new Metadata();
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + System.getenv("SERVICE_ACCESS_TOKEN"));
            var stub = QuotaCommandServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata)).withDeadlineAfter(5, TimeUnit.SECONDS);
            var request = QuotaCommandRequest.newBuilder().setTenantId(args[1]).setOperationId(args[2])
                    .setQuotaCode("max_users").setAmount(1).setPurpose(QuotaPurpose.TENANT_ADMIN_INITIALIZATION).build();
            var result = args[3].equals("consume") ? stub.consume(request) : stub.release(request);
            int expected = args[3].equals("consume") ? 1 : 0;
            if (result.getUsage() != expected || result.getLimit() != 1) {
                throw new AssertionError("Expected limit=1 and usage=" + expected);
            }
            System.out.println("Quota probe " + args[3] + " verified limit=1 usage=" + expected);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
