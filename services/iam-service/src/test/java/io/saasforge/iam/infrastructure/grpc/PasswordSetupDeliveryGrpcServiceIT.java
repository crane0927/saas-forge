package io.saasforge.iam.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.saasforge.contracts.iam.passwordsetup.v1.DeliverPasswordSetupRequest;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupDeliveryResult;
import io.saasforge.contracts.iam.passwordsetup.v1.PasswordSetupServiceGrpc;
import io.saasforge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PasswordSetupDeliveryGrpcServiceIT {
    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");
    private static final UUID TENANT_ACCESS_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5201");
    private static final UUID OTHER_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5202");
    private static final UUID REQUEST_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5203");
    private static final UUID IDENTITY_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5204");
    private static final UUID JTI =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d5205");

    private RSAKey key;
    private Server server;
    private ManagedChannel channel;
    private final AtomicBoolean serviceTokenRevoked = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("password-setup-delivery-key").generate();
        ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                new ServiceAccessTokenSignatureVerifier(
                        this::verificationKey, Clock.fixed(NOW, ZoneOffset.UTC),
                        "https://iam.test", "saasforge-api", Duration.ofSeconds(30)),
                (clientId, kid) -> serviceTokenRevoked.get());
        OAuthClient tenantAccess = OAuthClient.register(
                        ReservedServiceClient.TENANT_ACCESS.displayName(),
                        ReservedServiceClient.TENANT_ACCESS.allowedScopes(), NOW)
                .identifiedBy(TENANT_ACCESS_CLIENT_ID);
        OAuthClient other = OAuthClient.register(
                        "other-service", ReservedServiceClient.TENANT_ACCESS.allowedScopes(), NOW)
                .identifiedBy(OTHER_CLIENT_ID);
        OAuthClientRepository clients = Mockito.mock(OAuthClientRepository.class);
        when(clients.findById(TENANT_ACCESS_CLIENT_ID)).thenReturn(Optional.of(tenantAccess));
        when(clients.findById(OTHER_CLIENT_ID)).thenReturn(Optional.of(other));

        PasswordSetupDeliveryService application = Mockito.mock(PasswordSetupDeliveryService.class);
        when(application.deliver(TENANT_ACCESS_CLIENT_ID, REQUEST_ID, IDENTITY_ID, null))
                .thenReturn(io.saasforge.iam.application.authentication.PasswordSetupDeliveryResult.PASSWORD_READY);
        PasswordSetupDeliveryGrpcService grpc = new PasswordSetupDeliveryGrpcService(application);
        PasswordSetupDeliveryServerInterceptor interceptor =
                new PasswordSetupDeliveryServerInterceptor(tokens, clients);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(grpc, interceptor)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() throws Exception {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void acceptsOnlyTenantAccessTokenWithPasswordSetupWriteScope() throws Exception {
        assertEquals(PasswordSetupDeliveryResult.PASSWORD_READY,
                stub(serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:password-setup:write"))
                        .deliverPasswordSetup(request()).getResult());

        StatusRuntimeException wrongScope = assertThrows(StatusRuntimeException.class,
                () -> stub(serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:identity:write"))
                        .deliverPasswordSetup(request()));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());

        StatusRuntimeException wrongClient = assertThrows(StatusRuntimeException.class,
                () -> stub(serviceToken(OTHER_CLIENT_ID, "iam:password-setup:write"))
                        .deliverPasswordSetup(request()));
        assertEquals(Status.Code.PERMISSION_DENIED, wrongClient.getStatus().getCode());

        StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                () -> PasswordSetupServiceGrpc.newBlockingStub(channel).deliverPasswordSetup(request()));
        assertEquals(Status.Code.UNAUTHENTICATED, missing.getStatus().getCode());
    }

    @Test
    void immediatelyRejectsPreviouslyIssuedTokenAfterClientRevocation() throws Exception {
        String token = serviceToken(TENANT_ACCESS_CLIENT_ID, "iam:password-setup:write");
        stub(token).deliverPasswordSetup(request());

        serviceTokenRevoked.set(true);

        StatusRuntimeException revoked = assertThrows(
                StatusRuntimeException.class, () -> stub(token).deliverPasswordSetup(request()));
        assertEquals(Status.Code.UNAUTHENTICATED, revoked.getStatus().getCode());
    }

    @Test
    void requestContractContainsOnlyRequestAndIdentityIds() {
        assertEquals(List.of("request_id", "identity_id"),
                DeliverPasswordSetupRequest.getDescriptor().getFields().stream()
                        .map(field -> field.getName()).toList());
    }

    private PasswordSetupServiceGrpc.PasswordSetupServiceBlockingStub stub(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return PasswordSetupServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static DeliverPasswordSetupRequest request() {
        return DeliverPasswordSetupRequest.newBuilder()
                .setRequestId(REQUEST_ID.toString())
                .setIdentityId(IDENTITY_ID.toString())
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
}
