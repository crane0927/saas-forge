package io.saasforge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleRequest;
import io.saasforge.contracts.iam.authorization.v1.CheckPlatformRoleResponse;
import io.saasforge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saasforge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.sdk.auth.GrpcPlatformRoleChecker;
import io.saasforge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saasforge.sdk.auth.PlatformRequestAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.UserAccessTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformAuthorizationGrpcServiceIT {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID USER_JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final UUID SERVICE_JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92");
    private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private final AtomicBoolean assigned = new AtomicBoolean(true);
    private final AtomicReference<String> checkedRole = new AtomicReference<>();
    private RSAKey key;
    private Server server;
    private ManagedChannel channel;
    private PlatformRoleAuthorizationService authorizationService;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("platform-key").generate();
        ServiceAccessTokenVerifier serviceTokens = new ServiceAccessTokenVerifier(
                this::verificationKey,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saasforge-api",
                Duration.ofSeconds(30));
        PlatformAuthorizationServerInterceptor interceptor =
                new PlatformAuthorizationServerInterceptor(serviceTokens);
        PlatformRoleAssignmentRepository roles = new PlatformRoleAssignmentRepository() {
            @Override
            public PlatformRoleAssignment grant(PlatformRoleAssignment assignment) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasActiveAssignment(UUID identityId, String roleKey, Instant at) {
                checkedRole.set(roleKey);
                return IDENTITY_ID.equals(identityId)
                        && PLATFORM_ADMIN.equals(roleKey)
                        && assigned.get();
            }
        };
        authorizationService = new PlatformRoleAuthorizationService(roles, Clock.fixed(NOW, ZoneOffset.UTC));
        PlatformAuthorizationGrpcService grpcService = new PlatformAuthorizationGrpcService(authorizationService);
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(grpcService, interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
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
    void callingBoundaryUsesPlatformTokenAndLiveExactIamRole() throws Exception {
        PlatformRequestAuthorizer authorizer = authorizer(serviceToken("iam:platform-role:read"));

        assertEquals(IDENTITY_ID, authorizer.authorize(userToken(Map.of()), PLATFORM_ADMIN));
        assertEquals(PLATFORM_ADMIN, checkedRole.get());

        assigned.set(false);
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> authorizer.authorize(userToken(Map.of()), PLATFORM_ADMIN));

        assigned.set(true);
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> authorizer.authorize(userToken(Map.of()), "BILLING_ADMIN"));
    }

    @Test
    void rejectsTenantClaimsWrongScopeBadTokenAndIamOutage() throws Exception {
        PlatformRequestAuthorizer validAuthorizer = authorizer(serviceToken("iam:platform-role:read"));
        assertThrows(PlatformAuthorizationDeniedException.class, () -> validAuthorizer.authorize(
                userToken(Map.of("membershipId", IDENTITY_ID.toString(), "tenantId", IDENTITY_ID.toString())),
                PLATFORM_ADMIN));

        PlatformRequestAuthorizer wrongScope = authorizer(serviceToken("iam:identity:write"));
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> wrongScope.authorize(userToken(Map.of()), PLATFORM_ADMIN));

        PlatformRequestAuthorizer badServiceToken = authorizer("not-a-jwt");
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> badServiceToken.authorize(userToken(Map.of()), PLATFORM_ADMIN));

        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> validAuthorizer.authorize(userToken(Map.of()), PLATFORM_ADMIN));
    }

    @Test
    void serviceRequiresBearerAndExactScopeAndContractExposesOnlyDecision() throws Exception {
        var client = PlatformAuthorizationServiceGrpc.newBlockingStub(channel);
        StatusRuntimeException missingToken = assertThrows(StatusRuntimeException.class,
                () -> client.checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                        .setIdentityId(IDENTITY_ID.toString())
                        .setRoleKey(PLATFORM_ADMIN)
                        .build()));
        assertEquals(Status.Code.UNAUTHENTICATED, missingToken.getStatus().getCode());

        Metadata malformedMetadata = new Metadata();
        malformedMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Basic token");
        StatusRuntimeException malformedToken = assertThrows(StatusRuntimeException.class,
                () -> client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(malformedMetadata))
                        .checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                                .setIdentityId(IDENTITY_ID.toString())
                                .setRoleKey(PLATFORM_ADMIN)
                                .build()));
        assertEquals(Status.Code.UNAUTHENTICATED, malformedToken.getStatus().getCode());

        Metadata wrongScopeMetadata = new Metadata();
        wrongScopeMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + serviceToken("iam:identity:write"));
        StatusRuntimeException wrongScope = assertThrows(StatusRuntimeException.class,
                () -> client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(wrongScopeMetadata))
                        .checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                                .setIdentityId(IDENTITY_ID.toString())
                                .setRoleKey(PLATFORM_ADMIN)
                                .build()));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());

        assertEquals(java.util.Set.of("identity_id", "role_key"),
                CheckPlatformRoleRequest.getDescriptor().getFields().stream()
                        .map(field -> field.getName())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.List.of("allowed"),
                CheckPlatformRoleResponse.getDescriptor().getFields().stream()
                        .map(field -> field.getName())
                        .toList());
        assertFalse(CheckPlatformRoleResponse.getDefaultInstance().getAllowed());
    }

    @Test
    void rejectsInvalidCallerConfigurationAndRequestFields() throws Exception {
        var stub = PlatformAuthorizationServiceGrpc.newBlockingStub(channel);
        assertThrows(IllegalArgumentException.class, () -> new GrpcPlatformRoleChecker(null, () -> "token"));
        assertThrows(IllegalArgumentException.class, () -> new GrpcPlatformRoleChecker(stub, null));

        GrpcPlatformRoleChecker checker = new GrpcPlatformRoleChecker(stub, () -> "token");
        assertThrows(IllegalArgumentException.class, () -> checker.isAllowed(null, PLATFORM_ADMIN));
        assertThrows(IllegalArgumentException.class, () -> checker.isAllowed(IDENTITY_ID, null));
        assertThrows(IllegalArgumentException.class, () -> checker.isAllowed(IDENTITY_ID, " "));
        assertThrows(IllegalStateException.class,
                () -> new GrpcPlatformRoleChecker(stub, () -> null).isAllowed(IDENTITY_ID, PLATFORM_ADMIN));
        assertThrows(IllegalStateException.class,
                () -> new GrpcPlatformRoleChecker(stub, () -> " ").isAllowed(IDENTITY_ID, PLATFORM_ADMIN));

        UserAccessTokenVerifier userTokens = new UserAccessTokenVerifier(
                this::verificationKey,
                (jti, kid) -> false,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saasforge-api",
                Duration.ofSeconds(30));
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformRequestAuthorizer(null, checker));
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformRequestAuthorizer(userTokens, null));
        PlatformRequestAuthorizer authorizer = new PlatformRequestAuthorizer(userTokens, checker);
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> authorizer.authorize(userToken(Map.of()), null));
        assertThrows(PlatformAuthorizationDeniedException.class,
                () -> authorizer.authorize(userToken(Map.of()), "platform_admin"));
    }

    @Test
    void rejectsNonCanonicalIdentityAndInvalidRoleAtIamBoundary() throws Exception {
        Metadata metadata = new Metadata();
        metadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + serviceToken("iam:platform-role:read"));
        var client = PlatformAuthorizationServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

        assertInvalidArgument(client, UUID.randomUUID().toString(), PLATFORM_ADMIN);
        assertInvalidArgument(client, IDENTITY_ID.toString().toUpperCase(), PLATFORM_ADMIN);
        assertInvalidArgument(client, IDENTITY_ID.toString(), "");
        assertInvalidArgument(client, IDENTITY_ID.toString(), "platform_admin");
        assertThrows(IllegalArgumentException.class,
                () -> authorizationService.isAllowed(null, PLATFORM_ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> authorizationService.isAllowed(UUID.randomUUID(), PLATFORM_ADMIN));
        assertThrows(IllegalArgumentException.class,
                () -> authorizationService.isAllowed(IDENTITY_ID, null));
    }

    private PlatformRequestAuthorizer authorizer(String serviceToken) {
        UserAccessTokenVerifier userTokens = new UserAccessTokenVerifier(
                this::verificationKey,
                (jti, kid) -> false,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saasforge-api",
                Duration.ofSeconds(30));
        GrpcPlatformRoleChecker roles = new GrpcPlatformRoleChecker(
                PlatformAuthorizationServiceGrpc.newBlockingStub(channel),
                () -> serviceToken);
        return new PlatformRequestAuthorizer(userTokens, roles);
    }

    private static void assertInvalidArgument(
            PlatformAuthorizationServiceGrpc.PlatformAuthorizationServiceBlockingStub client,
            String identityId,
            String roleKey) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                () -> client.checkPlatformRole(CheckPlatformRoleRequest.newBuilder()
                        .setIdentityId(identityId)
                        .setRoleKey(roleKey)
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode());
    }

    private Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!key.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
    }

    private String userToken(Map<String, Object> extraClaims) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saasforge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(900)))
                .claim("identityId", IDENTITY_ID.toString())
                .jwtID(USER_JTI.toString());
        extraClaims.forEach(claims::claim);
        return "Bearer " + sign(JOSEObjectType.JWT, claims.build());
    }

    private String serviceToken(String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saasforge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(SERVICE_JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", scope)
                .build();
        return sign(new JOSEObjectType("at+jwt"), claims);
    }

    private String sign(JOSEObjectType type, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(type)
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
