package io.saasforge.entitlement.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandResponse;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saasforge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saasforge.entitlement.application.quota.QuotaCommandResult;
import io.saasforge.entitlement.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Entitlement 真实 gRPC 接收端与 Redis Revocation Index 的 fail-closed 验收。 */
@Testcontainers
class ServiceAccessTokenReceiverRedisIT {
    private static final Instant NOW = Instant.parse("2026-08-28T07:00:00Z");
    private static final UUID TENANT_ACCESS_CLIENT_ID = uuidV7(1);
    private static final UUID TENANT_ID = uuidV7(2);
    private static final UUID OPERATION_ID = uuidV7(3);
    private static final UUID JTI = uuidV7(4);
    private static final String REQUIRED_SCOPE = "entitlement:quota:write";
    private static final String PREFIX = "sf:test:iam-service:";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "no", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    private final AtomicInteger commandCalls = new AtomicInteger();
    private final AtomicReference<UUID> operationTarget = new AtomicReference<>();
    private RSAKey key;
    private LettuceConnectionFactory redisConnection;
    private StringRedisTemplate redis;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("entitlement-receiver-key").generate();
        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO)
                .build();
        redisConnection = new LettuceConnectionFactory(redisConfiguration, clientConfiguration);
        redisConnection.afterPropertiesSet();
        redis = new StringRedisTemplate(redisConnection);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");

        ServiceAccessTokenAuthorizer tokens = new ServiceAccessTokenAuthorizer(
                new ServiceAccessTokenSignatureVerifier(
                        this::verificationKey,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "https://iam.test",
                        "saasforge-api",
                        Duration.ofSeconds(30)),
                new RedisServiceAccessTokenRevocationChecker(redis, "test"));
        QuotaCommandApplicationService commands = mock(QuotaCommandApplicationService.class);
        when(commands.consume(any(), any(), anyString(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    commandCalls.incrementAndGet();
                    operationTarget.set(invocation.getArgument(1));
                    return new QuotaCommandResult(1, 2, false);
                });

        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new QuotaCommandGrpcService(commands), new QuotaCommandServerInterceptor(tokens)))
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
        if (redisConnection != null) {
            redisConnection.destroy();
        }
    }

    @Test
    void authorizesWithoutUserTenantContextAndFailsClosedBeforeQuotaCommand() throws Exception {
        String token = serviceToken(REQUIRED_SCOPE);
        assertServiceTokenHasNoUserTenantContext(token);
        QuotaCommandResponse response = consume(token);
        assertEquals(1, response.getUsage());
        assertEquals(2, response.getLimit());
        assertFalse(response.getReplayed());
        assertEquals(TENANT_ID, operationTarget.get());
        assertEquals(1, commandCalls.get());

        assertStatus(Status.Code.PERMISSION_DENIED,
                () -> consume(serviceToken("tenant-access:tenant:read")));
        assertEquals(1, commandCalls.get());

        redis.opsForValue().set(PREFIX + "oauth-client-revocation:v1:" + TENANT_ACCESS_CLIENT_ID, "1");
        assertUnauthenticated(token);
        redis.delete(PREFIX + "oauth-client-revocation:v1:" + TENANT_ACCESS_CLIENT_ID);

        redis.opsForValue().set(PREFIX + "signing-kid-revocation:v1:" + digest(key.getKeyID()), "1");
        assertUnauthenticated(token);
        redis.delete(PREFIX + "signing-kid-revocation:v1:" + digest(key.getKeyID()));

        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "0");
        assertUnauthenticated(token);

        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        REDIS.stop();
        assertUnauthenticated(token);
    }

    private void assertUnauthenticated(String token) {
        assertStatus(Status.Code.UNAUTHENTICATED, () -> consume(token));
        assertEquals(1, commandCalls.get());
    }

    private QuotaCommandResponse consume(String token) {
        return QuotaCommandServiceGrpc.newBlockingStub(channel)
                .withInterceptors(authorization(token))
                .consume(QuotaCommandRequest.newBuilder()
                        .setTenantId(TENANT_ID.toString())
                        .setQuotaCode("max_users")
                        .setAmount(1)
                        .setOperationId(OPERATION_ID.toString())
                        .setPurpose(QuotaPurpose.TENANT_ADMIN_INITIALIZATION)
                        .build());
    }

    private static io.grpc.ClientInterceptor authorization(String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return MetadataUtils.newAttachHeadersInterceptor(metadata);
    }

    private Optional<ServiceJwtVerificationKey> verificationKey(String kid) {
        if (!key.getKeyID().equals(kid)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
    }

    private String serviceToken(String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saasforge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(JTI.toString())
                .subject(TENANT_ACCESS_CLIENT_ID.toString())
                .claim("client_id", TENANT_ACCESS_CLIENT_ID.toString())
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

    private static void assertServiceTokenHasNoUserTenantContext(String token) throws Exception {
        var claims = SignedJWT.parse(token).getJWTClaimsSet().getClaims();
        assertFalse(claims.containsKey("identityId"));
        assertFalse(claims.containsKey("membershipId"));
        assertFalse(claims.containsKey("tenantId"));
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertStatus(Status.Code expected, org.junit.jupiter.api.function.Executable invocation) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, invocation);
        assertEquals(expected, exception.getStatus().getCode());
    }

    private static UUID uuidV7(long sequence) {
        return UUID.fromString("0198f89c-8300-7000-8000-" + String.format("%012x", sequence));
    }
}
