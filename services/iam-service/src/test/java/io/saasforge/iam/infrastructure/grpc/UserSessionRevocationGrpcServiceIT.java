package io.saasforge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.iam.session.v1.MembershipTarget;
import io.saasforge.contracts.iam.session.v1.RecoverUserSessionRevocationRequest;
import io.saasforge.contracts.iam.session.v1.ReleaseUserSessionFenceRequest;
import io.saasforge.contracts.iam.session.v1.RevokeUserSessionsRequest;
import io.saasforge.contracts.iam.session.v1.TenantTarget;
import io.saasforge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saasforge.iam.application.authentication.RevocationFenceConflictException;
import io.saasforge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saasforge.iam.application.authentication.UserSessionRevocationRecoveryRequiredException;
import io.saasforge.iam.application.authentication.UserSessionRevocationResult;
import io.saasforge.iam.application.authentication.UserSessionRevocationService;
import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class UserSessionRevocationGrpcServiceIT {
    private static final Instant NOW = Instant.parse("2026-08-26T06:00:00Z");
    private static final UUID TENANT_ACCESS_CLIENT_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000001");
    private static final UUID OTHER_CLIENT_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000002");
    private static final UUID REQUEST_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000003");
    private static final UUID SECOND_REQUEST_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000004");
    private static final UUID RELEASE_REQUEST_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000005");
    private static final UUID MEMBERSHIP_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000006");
    private static final UUID TENANT_ID =
            UUID.fromString("01991b28-7c00-7000-8000-000000000007");
    private static final UUID JTI =
            UUID.fromString("01991b28-7c00-7000-8000-000000000008");

    private RSAKey key;
    private UserSessionRevocationService application;
    private Server server;
    private ManagedChannel channel;
    private final AtomicBoolean serviceTokenRevoked = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("session-revocation-key").generate();
        ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                new ServiceAccessTokenSignatureVerifier(
                        this::verificationKey,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "https://iam.test",
                        "saasforge-api",
                        Duration.ofSeconds(30)),
                (clientId, kid) -> serviceTokenRevoked.get());
        OAuthClient tenantAccess = OAuthClient.register(
                        ReservedServiceClient.TENANT_ACCESS.displayName(),
                        ReservedServiceClient.TENANT_ACCESS.allowedScopes(),
                        NOW)
                .identifiedBy(TENANT_ACCESS_CLIENT_ID);
        OAuthClient other = OAuthClient.register(
                        "other-service", ReservedServiceClient.TENANT_ACCESS.allowedScopes(), NOW)
                .identifiedBy(OTHER_CLIENT_ID);
        OAuthClientRepository clients = Mockito.mock(OAuthClientRepository.class);
        when(clients.findById(TENANT_ACCESS_CLIENT_ID)).thenReturn(Optional.of(tenantAccess));
        when(clients.findById(OTHER_CLIENT_ID)).thenReturn(Optional.of(other));

        application = Mockito.mock(UserSessionRevocationService.class);
        UserSessionRevocationGrpcService grpc = new UserSessionRevocationGrpcService(application);
        UserSessionRevocationServerInterceptor interceptor =
                new UserSessionRevocationServerInterceptor(tokens, clients);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(grpc, interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void exposesPendingAndCompletedOutcomesForExactTargets() throws Exception {
        RevocationFenceTarget membership = RevocationFenceTarget.membership(MEMBERSHIP_ID, TENANT_ID);
        RevocationFenceTarget tenant = RevocationFenceTarget.tenant(TENANT_ID);
        when(application.revoke(REQUEST_ID, membership)).thenReturn(UserSessionRevocationResult.pending(7));
        when(application.revoke(SECOND_REQUEST_ID, tenant))
                .thenReturn(UserSessionRevocationResult.completed(2, 3));

        var client = authorizedStub();
        var pending = client.revokeUserSessions(membershipRequest(REQUEST_ID));
        assertEquals(7, pending.getPending().getRetryAfterSeconds());

        var completed = client.revokeUserSessions(tenantRequest(SECOND_REQUEST_ID));
        assertEquals(2, completed.getCompleted().getRevokedFamilyCount());
        assertEquals(3, completed.getCompleted().getRevokedJtiCount());
    }

    @Test
    void immediatelyRejectsPreviouslyIssuedTokenAfterClientRevocation() throws Exception {
        when(application.revoke(
                        REQUEST_ID, RevocationFenceTarget.membership(MEMBERSHIP_ID, TENANT_ID)))
                .thenReturn(UserSessionRevocationResult.completed(0, 0));
        String token = serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:sessions:write");
        var client = stub("Bearer " + token);
        client.revokeUserSessions(membershipRequest(REQUEST_ID));

        serviceTokenRevoked.set(true);

        assertStatus(Status.Code.UNAUTHENTICATED,
                () -> client.revokeUserSessions(membershipRequest(REQUEST_ID)));
    }

    @Test
    void forwardsExplicitRecoveryAndGenerationSafeReleaseWithExactTargets() throws Exception {
        var client = authorizedStub();
        client.recoverUserSessionRevocation(RecoverUserSessionRevocationRequest.newBuilder()
                .setRevocationRequestId(REQUEST_ID.toString())
                .setMembershipTarget(membershipTarget())
                .build());
        client.releaseUserSessionFence(ReleaseUserSessionFenceRequest.newBuilder()
                .setReleaseRequestId(RELEASE_REQUEST_ID.toString())
                .setRevocationRequestId(REQUEST_ID.toString())
                .setTenantTarget(tenantTarget())
                .build());

        verify(application).recover(
                REQUEST_ID, RevocationFenceTarget.membership(MEMBERSHIP_ID, TENANT_ID));
        verify(application).release(
                RELEASE_REQUEST_ID, REQUEST_ID, RevocationFenceTarget.tenant(TENANT_ID));
    }

    @Test
    void rejectsMissingTargetsAndNonCanonicalUuidV7Values() throws Exception {
        var client = authorizedStub();
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.revokeUserSessions(
                RevokeUserSessionsRequest.newBuilder().setRequestId(REQUEST_ID.toString()).build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.recoverUserSessionRevocation(
                RecoverUserSessionRevocationRequest.newBuilder()
                        .setRevocationRequestId(REQUEST_ID.toString())
                        .build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.releaseUserSessionFence(
                ReleaseUserSessionFenceRequest.newBuilder()
                        .setReleaseRequestId(RELEASE_REQUEST_ID.toString())
                        .setRevocationRequestId(REQUEST_ID.toString())
                        .build()));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> client.revokeUserSessions(
                membershipRequest(UUID.randomUUID())));
    }

    @Test
    void mapsApplicationFailuresWithoutLeakingInternalDetails() throws Exception {
        var client = authorizedStub();
        RevokeUserSessionsRequest revoke = membershipRequest(REQUEST_ID);
        RecoverUserSessionRevocationRequest recover = RecoverUserSessionRevocationRequest.newBuilder()
                .setRevocationRequestId(REQUEST_ID.toString())
                .setMembershipTarget(membershipTarget())
                .build();
        ReleaseUserSessionFenceRequest release = ReleaseUserSessionFenceRequest.newBuilder()
                .setReleaseRequestId(RELEASE_REQUEST_ID.toString())
                .setRevocationRequestId(REQUEST_ID.toString())
                .setMembershipTarget(membershipTarget())
                .build();

        doThrow(new RevocationFenceConflictException()).when(application).revoke(any(), any());
        assertStatus(Status.Code.FAILED_PRECONDITION, () -> client.revokeUserSessions(revoke));
        doThrow(new RevocationIndexUnavailableException()).when(application).revoke(any(), any());
        assertStatus(Status.Code.UNAVAILABLE, () -> client.revokeUserSessions(revoke));
        doThrow(new IllegalStateException("sensitive")).when(application).revoke(any(), any());
        assertStatus(Status.Code.INTERNAL, () -> client.revokeUserSessions(revoke));

        doThrow(new UserSessionRevocationRecoveryRequiredException())
                .when(application).recover(any(), any());
        assertStatus(Status.Code.FAILED_PRECONDITION,
                () -> client.recoverUserSessionRevocation(recover));
        doThrow(new DataAccessResourceFailureException("database detail"))
                .when(application).recover(any(), any());
        assertStatus(Status.Code.UNAVAILABLE, () -> client.recoverUserSessionRevocation(recover));
        doThrow(new IllegalStateException("sensitive"))
                .when(application).recover(any(), any());
        assertStatus(Status.Code.INTERNAL, () -> client.recoverUserSessionRevocation(recover));

        doThrow(new RevocationFenceConflictException())
                .when(application).release(any(), any(), any());
        assertStatus(Status.Code.FAILED_PRECONDITION, () -> client.releaseUserSessionFence(release));
        doThrow(new RevocationIndexUnavailableException())
                .when(application).release(any(), any(), any());
        assertStatus(Status.Code.UNAVAILABLE, () -> client.releaseUserSessionFence(release));
        doThrow(new IllegalStateException("sensitive"))
                .when(application).release(any(), any(), any());
        assertStatus(Status.Code.INTERNAL, () -> client.releaseUserSessionFence(release));
    }

    @Test
    void requiresBearerTokenExactScopeAndReservedTenantAccessClient() throws Exception {
        assertStatus(Status.Code.UNAUTHENTICATED, () -> UserSessionRevocationServiceGrpc
                .newBlockingStub(channel).revokeUserSessions(membershipRequest(REQUEST_ID)));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> stub("Basic token")
                .revokeUserSessions(membershipRequest(REQUEST_ID)));
        assertStatus(Status.Code.UNAUTHENTICATED, () -> stub("Bearer not-a-jwt")
                .revokeUserSessions(membershipRequest(REQUEST_ID)));
        assertStatus(Status.Code.PERMISSION_DENIED, () -> stub("Bearer " + serviceToken(
                TENANT_ACCESS_CLIENT_ID, "iam:identity:write"))
                .revokeUserSessions(membershipRequest(REQUEST_ID)));
        assertStatus(Status.Code.PERMISSION_DENIED, () -> stub("Bearer " + serviceToken(
                OTHER_CLIENT_ID, "iam:sessions:write"))
                .revokeUserSessions(membershipRequest(REQUEST_ID)));
    }

    private UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub authorizedStub()
            throws Exception {
        return stub("Bearer " + serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:sessions:write"));
    }

    private UserSessionRevocationServiceGrpc.UserSessionRevocationServiceBlockingStub stub(String authorization) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), authorization);
        return UserSessionRevocationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static RevokeUserSessionsRequest membershipRequest(UUID requestId) {
        return RevokeUserSessionsRequest.newBuilder()
                .setRequestId(requestId.toString())
                .setMembershipTarget(membershipTarget())
                .build();
    }

    private static RevokeUserSessionsRequest tenantRequest(UUID requestId) {
        return RevokeUserSessionsRequest.newBuilder()
                .setRequestId(requestId.toString())
                .setTenantTarget(tenantTarget())
                .build();
    }

    private static MembershipTarget membershipTarget() {
        return MembershipTarget.newBuilder()
                .setMembershipId(MEMBERSHIP_ID.toString())
                .setTenantId(TENANT_ID.toString())
                .build();
    }

    private static TenantTarget tenantTarget() {
        return TenantTarget.newBuilder().setTenantId(TENANT_ID.toString()).build();
    }

    private Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!key.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
    }

    private String serviceToken(UUID clientId, String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saasforge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(JTI.toString())
                .subject(clientId.toString())
                .claim("client_id", clientId.toString())
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static void assertStatus(Status.Code expected, ThrowingCall call) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(expected, exception.getStatus().getCode());
        assertNull(exception.getStatus().getDescription());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
