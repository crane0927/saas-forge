package io.saasforge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipNotUsable;
import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ValidateMembershipRequest;
import io.saasforge.contracts.tenantaccess.membership.v1.ValidateMembershipResponse;
import io.saasforge.iam.application.authentication.TenantAccessUnavailableException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcMembershipValidationTest {
    private static final UUID IDENTITY_ID = uuidV7(1);
    private static final UUID MEMBERSHIP_ID = uuidV7(2);
    private static final UUID TENANT_ID = uuidV7(3);
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.ALLOW);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private Server server;
    private ManagedChannel channel;
    private GrpcMembershipValidation validation;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        authorization.set(headers.get(AUTHORIZATION));
                        return next.startCall(call, headers);
                    }
                })
                .addService(new TestService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        validation = new GrpcMembershipValidation(
                MembershipValidationServiceGrpc.newBlockingStub(channel), () -> "service-token");
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void sendsBearerTokenValidatesResponseAndNeverCachesAllowResult() {
        var first = validation.validate(IDENTITY_ID, MEMBERSHIP_ID).orElseThrow();
        var second = validation.validate(IDENTITY_ID, MEMBERSHIP_ID).orElseThrow();

        assertEquals(MEMBERSHIP_ID, first.membershipId());
        assertEquals(TENANT_ID, first.tenantId());
        assertEquals(first, second);
        assertEquals(2, calls.get());
        assertEquals("Bearer service-token", authorization.get());
    }

    @Test
    void preservesReasonFreeDenial() {
        mode.set(Mode.DENY);

        assertTrue(validation.validate(IDENTITY_ID, MEMBERSHIP_ID).isEmpty());
    }

    @Test
    void failsClosedForMissingTokenNetworkFailureAndIllegalResponses() throws Exception {
        var missingToken = new GrpcMembershipValidation(
                MembershipValidationServiceGrpc.newBlockingStub(channel), () -> " ");
        assertThrows(TenantAccessUnavailableException.class,
                () -> missingToken.validate(IDENTITY_ID, MEMBERSHIP_ID));

        mode.set(Mode.WRONG_MEMBERSHIP);
        assertThrows(TenantAccessUnavailableException.class,
                () -> validation.validate(IDENTITY_ID, MEMBERSHIP_ID));
        mode.set(Mode.INVALID_TENANT);
        assertThrows(TenantAccessUnavailableException.class,
                () -> validation.validate(IDENTITY_ID, MEMBERSHIP_ID));
        mode.set(Mode.NO_OUTCOME);
        assertThrows(TenantAccessUnavailableException.class,
                () -> validation.validate(IDENTITY_ID, MEMBERSHIP_ID));

        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThrows(TenantAccessUnavailableException.class,
                () -> validation.validate(IDENTITY_ID, MEMBERSHIP_ID));
    }

    @Test
    void rejectsInvalidCallerArgumentsAndConstruction() {
        var stub = MembershipValidationServiceGrpc.newBlockingStub(channel);
        assertThrows(IllegalArgumentException.class, () -> new GrpcMembershipValidation(null, () -> "token"));
        assertThrows(IllegalArgumentException.class, () -> new GrpcMembershipValidation(stub, null));
        assertThrows(IllegalArgumentException.class, () -> validation.validate(null, MEMBERSHIP_ID));
        assertThrows(IllegalArgumentException.class, () -> validation.validate(IDENTITY_ID, UUID.randomUUID()));
    }

    private final class TestService extends MembershipValidationServiceGrpc.MembershipValidationServiceImplBase {
        @Override
        public void validateMembership(
                ValidateMembershipRequest request,
                StreamObserver<ValidateMembershipResponse> responseObserver) {
            calls.incrementAndGet();
            ValidateMembershipResponse response = switch (mode.get()) {
                case ALLOW -> allowed(MEMBERSHIP_ID.toString(), TENANT_ID.toString());
                case DENY -> ValidateMembershipResponse.newBuilder()
                        .setMembershipNotUsable(MembershipNotUsable.getDefaultInstance())
                        .build();
                case WRONG_MEMBERSHIP -> allowed(uuidV7(4).toString(), TENANT_ID.toString());
                case INVALID_TENANT -> allowed(MEMBERSHIP_ID.toString(), "invalid");
                case NO_OUTCOME -> ValidateMembershipResponse.getDefaultInstance();
            };
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private static ValidateMembershipResponse allowed(String membershipId, String tenantId) {
        return ValidateMembershipResponse.newBuilder()
                .setValidatedMembership(
                        io.saasforge.contracts.tenantaccess.membership.v1.ValidatedMembership.newBuilder()
                                .setMembershipId(membershipId)
                                .setTenantId(tenantId))
                .build();
    }

    private static UUID uuidV7(long sequence) {
        return UUID.fromString("019535d9-" + String.format("%04x", sequence)
                + "-7000-8000-" + String.format("%012x", sequence));
    }

    private enum Mode {
        ALLOW,
        DENY,
        WRONG_MEMBERSHIP,
        INVALID_TENANT,
        NO_OUTCOME
    }
}
