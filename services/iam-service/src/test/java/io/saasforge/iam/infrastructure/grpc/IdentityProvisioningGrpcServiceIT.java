package io.saasforge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.saasforge.contracts.iam.identity.v1.EnsureIdentityRequest;
import io.saasforge.contracts.iam.identity.v1.IdentityCredentialStatus;
import io.saasforge.contracts.iam.identity.v1.IdentityProvisioningServiceGrpc;
import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.application.identity.EnsureIdentityService;
import io.saasforge.iam.domain.client.ClientSecret;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityProvisioningFact;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityProvisioningGrpcServiceIT {
    private static final Instant NOW = Instant.parse("2026-08-22T05:00:00Z");
    private static final UUID TENANT_ACCESS_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID OTHER_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID REQUEST_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final UUID IDENTITY_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92");
    private static final UUID JTI =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c93");

    private RSAKey key;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("identity-provisioning-key").generate();
        ServiceAccessTokenVerifier tokens = new ServiceAccessTokenVerifier(
                this::verificationKey,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saasforge-api",
                Duration.ofSeconds(30));

        OAuthClient tenantAccess = OAuthClient.register(
                        ReservedServiceClient.TENANT_ACCESS.displayName(),
                        ReservedServiceClient.TENANT_ACCESS.allowedScopes(),
                        NOW)
                .identifiedBy(TENANT_ACCESS_CLIENT_ID);
        OAuthClient other = OAuthClient.register(
                        "other-service", ReservedServiceClient.TENANT_ACCESS.allowedScopes(), NOW)
                .identifiedBy(OTHER_CLIENT_ID);
        OAuthClientRepository clients = new FakeOAuthClientRepository(Map.of(
                TENANT_ACCESS_CLIENT_ID, tenantAccess,
                OTHER_CLIENT_ID, other));

        IdentityProvisioningRepository requests = new EmptyIdentityProvisioningRepository();
        IdentityRepository identities = new EmptyIdentityRepository();
        EnsureIdentityService application = new EnsureIdentityService(
                requests, identities, Clock.fixed(NOW, ZoneOffset.UTC));

        IdentityProvisioningGrpcService grpcService = new IdentityProvisioningGrpcService(application);
        IdentityProvisioningServerInterceptor interceptor =
                new IdentityProvisioningServerInterceptor(tokens, clients);
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
    void acceptsOnlyTenantAccessTokenWithIdentityWriteScope() throws Exception {
        var response = stub(serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:identity:write"))
                .ensureIdentity(request());
        assertEquals(IDENTITY_ID.toString(), response.getIdentityId());
        assertEquals(IdentityCredentialStatus.SETUP_ALLOWED, response.getCredentialStatus());

        StatusRuntimeException wrongScope = assertThrows(StatusRuntimeException.class,
                () -> stub(serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:platform-role:read"))
                        .ensureIdentity(request()));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());

        StatusRuntimeException wrongClient = assertThrows(StatusRuntimeException.class,
                () -> stub(serviceToken(OTHER_CLIENT_ID, "iam:identity:write"))
                        .ensureIdentity(request()));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongClient.getStatus().getCode());

        StatusRuntimeException missingToken = assertThrows(StatusRuntimeException.class,
                () -> IdentityProvisioningServiceGrpc.newBlockingStub(channel).ensureIdentity(request()));
        assertEquals(Status.Code.UNAUTHENTICATED, missingToken.getStatus().getCode());
    }

    @Test
    void rejectsInvalidRequestAndKeepsContractMinimal() throws Exception {
        var client = stub(serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:identity:write"));
        StatusRuntimeException invalid = assertThrows(StatusRuntimeException.class,
                () -> client.ensureIdentity(EnsureIdentityRequest.newBuilder()
                        .setRequestId(UUID.randomUUID().toString())
                        .setEmail("admin@example.test")
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, invalid.getStatus().getCode());

        assertEquals(List.of("request_id", "email", "display_name"),
                EnsureIdentityRequest.getDescriptor().getFields().stream()
                        .map(field -> field.getName())
                        .toList());
    }

    private IdentityProvisioningServiceGrpc.IdentityProvisioningServiceBlockingStub stub(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return IdentityProvisioningServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static EnsureIdentityRequest request() {
        return EnsureIdentityRequest.newBuilder()
                .setRequestId(REQUEST_ID.toString())
                .setEmail("admin@example.test")
                .setDisplayName("Tenant Admin")
                .build();
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

    private record FakeOAuthClientRepository(Map<UUID, OAuthClient> clients) implements OAuthClientRepository {
        @Override
        public OAuthClient create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OAuthClient createWithId(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void lockReservedClientBootstrap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OAuthClientBootstrapState> findBootstrapState(UUID clientId) {
            return Optional.empty();
        }

        @Override
        public Optional<OAuthClient> findById(UUID clientId) {
            return Optional.ofNullable(clients.get(clientId));
        }

        @Override
        public Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at) {
            return Optional.empty();
        }

        @Override
        public ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(UUID clientId, Instant at) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyIdentityProvisioningRepository implements IdentityProvisioningRepository {
        @Override
        public void lockRequest(UUID callerClientId, UUID requestId) {
        }

        @Override
        public Optional<IdentityProvisioningFact> find(UUID callerClientId, UUID requestId) {
            return Optional.empty();
        }

        @Override
        public void create(IdentityProvisioningFact fact) {
        }
    }

    private static final class EmptyIdentityRepository implements IdentityRepository {
        @Override
        public Identity create(Identity identity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Identity findOrCreate(Identity identity) {
            return identity.identifiedBy(IDENTITY_ID);
        }

        @Override
        public Optional<Identity> findByEmail(NormalizedEmail email) {
            return Optional.empty();
        }

        @Override
        public void lockIdentity(UUID identityId) {
        }

        @Override
        public Optional<PasswordCredential> createFirstPassword(PasswordCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PasswordCredential create(PasswordCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PasswordCredential replaceInitialPassword(
                PasswordCredential initialCredential, PasswordCredential passwordCredential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidate(UUID credentialId, Instant invalidatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PasswordCredential> lockCredentials(UUID identityId) {
            return List.of();
        }

        @Override
        public List<PasswordCredential> findCredentials(UUID identityId) {
            return List.of();
        }

        @Override
        public Optional<PasswordCredential> findCredential(UUID credentialId) {
            return Optional.empty();
        }
    }
}
