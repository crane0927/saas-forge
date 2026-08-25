package io.saasforge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientBootstrapConflictException;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientBootstrapInput;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientBootstrapResult;
import io.saasforge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import io.saasforge.iam.application.authentication.IssuedAccessToken;
import io.saasforge.iam.application.authentication.RefreshRotationTransaction;
import io.saasforge.iam.application.authentication.RefreshTokenMaterial;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.DuplicateIdentityEmailException;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.session.RefreshTokenConsumption;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshRotation;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Testcontainers
@SpringJUnitConfig(IamPersistenceRepositoryIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IamPersistenceRepositoryIT {

    private static final String ARGON2ID_HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("saasforge")
            .withUsername("saasforge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    static {
        POSTGRES.start();
    }

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private RefreshTokenFamilyRepository refreshTokenFamilies;

    @Autowired
    private AccessTokenIssuanceRepository accessTokenIssuances;

    @Autowired
    private OAuthClientRepository clients;

    @Autowired
    private ReservedServiceClientBootstrapService reservedClientBootstrap;

    @Autowired
    private SigningKeyRepository signingKeys;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void persistsIdentityAndCredentialInvariantsWithDatabaseGeneratedUuidV7() throws SQLException {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        Identity identity = identities.create(Identity.register(" Admin@Example.Test ", "管理员", now));

        assertNotNull(identity.id());
        assertEquals(7, uuidVersion(identity.id()));
        assertEquals("admin@example.test", identities.findByEmail(identity.email()).orElseThrow().email().value());
        assertEquals("管理员", identities.findByEmail(identity.email()).orElseThrow().displayName());
        assertThrows(DuplicateIdentityEmailException.class,
                () -> identities.create(Identity.register("admin@example.test", "不会覆盖", now.plusSeconds(1))));

        Identity reused = identities.findOrCreate(Identity.register(" ADMIN@EXAMPLE.TEST ", "不会覆盖", now.plusSeconds(2)));
        assertEquals(identity.id(), reused.id());
        assertEquals("管理员", reused.displayName());

        Identity withoutDisplayName = identities.create(Identity.register("empty-name@example.test", null, now));
        Identity sameDisplayName = identities.create(Identity.register("same-name@example.test", "管理员", now));
        assertNull(withoutDisplayName.displayName());
        assertEquals("管理员", sameDisplayName.displayName());

        PasswordCredential initial = identities.create(PasswordCredential.initial(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now));
        PasswordCredential regular = identities.replaceInitialPassword(initial, PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(2)));

        assertNotNull(initial.id());
        assertNotNull(regular.id());
        assertThrows(IllegalStateException.class, () -> identities.create(PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(3))));
        assertThrows(IllegalStateException.class, () -> identities.replaceInitialPassword(initial, PasswordCredential.regular(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), now.plusSeconds(4))));

        var credentials = identities.findCredentials(identity.id());
        assertEquals(2, credentials.size());
        assertEquals(CredentialType.INITIAL_PLATFORM_PASSWORD, credentials.get(0).type());
        assertEquals(now.plusSeconds(2), credentials.get(0).invalidatedAt());
        assertEquals(CredentialType.PASSWORD, credentials.get(1).type());
        assertEquals(regular.id(), credentials.get(1).id());
    }

    @Test
    void atomicallyConsumesRefreshTokensCarriesContextAndRevokesReplayFamily() {
        Instant loginAt = Instant.parse("2026-08-20T01:00:00Z");
        Identity identity = identities.create(Identity.register("session-" + UUID.randomUUID() + "@example.test", null, loginAt));
        Sha256Digest first = digest(1);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identity.id(), null, null, loginAt), first, loginAt);

        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                refreshTokenFamilies.consume(first, loginAt.plusSeconds(1)).status());
        assertEquals(RefreshTokenConsumption.Status.REPLAYED,
                refreshTokenFamilies.consume(first, loginAt.plusSeconds(2)).status());
        assertNotNull(refreshTokenFamilies.findById(family.id()).orElseThrow().revokedAt());

        Sha256Digest presented = digest(2);
        RefreshTokenFamily rotating = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identity.id(), null, null, loginAt), presented, loginAt);
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshTokenConsumption rotated = refreshTokenFamilies.rotate(
                presented, digest(3), membershipId, tenantId, loginAt.plus(1, ChronoUnit.MINUTES));

        assertEquals(RefreshTokenConsumption.Status.CONSUMED, rotated.status());
        assertEquals(membershipId, rotated.family().membershipId());
        assertEquals(tenantId, rotated.family().tenantId());
        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), rotating.absoluteExpiresAt());
        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), rotated.family().absoluteExpiresAt());

        PasswordCredential initial = identities.create(PasswordCredential.initial(
                identity.id(), Argon2idPasswordHash.of(ARGON2ID_HASH), loginAt));
        Sha256Digest restrictedToken = digest(7);
        refreshTokenFamilies.create(RefreshTokenFamily.startInitialPasswordChange(
                identity.id(), initial.id(), loginAt, initial.expiresAt()), restrictedToken, loginAt);
        assertEquals(RefreshTokenConsumption.Status.PURPOSE_MISMATCH,
                refreshTokenFamilies.rotate(
                        restrictedToken, digest(8), null, null, loginAt.plusSeconds(1)).status());
        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                refreshTokenFamilies.consumeInitialPasswordChange(
                        restrictedToken, loginAt.plusSeconds(2)).status());
    }

    @Test
    void contextChangeCommittedBeforeRefreshLeavesPreparedTokenAndPresentedTokenUnpersisted() throws Exception {
        Instant loginAt = Instant.parse("2026-08-24T01:00:00Z");
        Instant refreshAt = loginAt.plusSeconds(1);
        UUID identityId = identities.create(Identity.register(
                "context-first-" + UUID.randomUUID() + "@example.test", null, loginAt)).id();
        UUID currentMembershipId = UUID.randomUUID();
        UUID currentTenantId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        UUID targetTenantId = UUID.randomUUID();
        Sha256Digest presentedDigest = digest(41);
        Sha256Digest successorDigest = digest(42);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        currentMembershipId, currentTenantId, loginAt),
                presentedDigest, loginAt);
        IssuedAccessToken preparedAccessToken = accessToken(refreshAt);
        RefreshRotationTransaction transaction = refreshRotationTransaction();
        CountDownLatch contextLocked = new CountDownLatch(1);
        CountDownLatch allowContextCommit = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var contextChange = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RefreshTokenFamilyContextChange result = refreshTokenFamilies.switchTenantContext(
                        family.id(), family.contextVersion(), targetMembershipId, targetTenantId);
                contextLocked.countDown();
                await(allowContextCommit);
                return result;
            }));
            assertTrue(contextLocked.await(5, TimeUnit.SECONDS));
            var refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
                    transaction.commit(
                            new RefreshTokenMaterial("presented", presentedDigest),
                            new RefreshTokenMaterial("successor", successorDigest), digest(43),
                            family.contextVersion(), currentMembershipId, currentTenantId,
                            preparedAccessToken, refreshAt, null)));

            allowContextCommit.countDown();

            assertEquals(RefreshTokenFamilyContextChange.Status.CHANGED,
                    contextChange.get(5, TimeUnit.SECONDS).status());
            assertEquals(RefreshRotation.Status.CONTEXT_CHANGED,
                    refresh.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        RefreshTokenFamily persisted = refreshTokenFamilies.findById(family.id()).orElseThrow();
        assertEquals(1, persisted.contextVersion());
        assertEquals(targetMembershipId, persisted.membershipId());
        assertEquals(targetTenantId, persisted.tenantId());
        assertEquals(loginAt, persisted.lastUsedAt());
        assertTrue(accessTokenIssuances.findByJti(preparedAccessToken.jti()).isEmpty());
        assertNull(tokenConsumedAt(presentedDigest));
        assertEquals(0, tokenCount(successorDigest));
    }

    @Test
    void refreshHoldingFamilyLockCommitsBeforeContextChangeWithoutOverwritingTheNewContext() throws Exception {
        Instant loginAt = Instant.parse("2026-08-24T02:00:00Z");
        Instant refreshAt = loginAt.plusSeconds(1);
        UUID identityId = identities.create(Identity.register(
                "refresh-first-" + UUID.randomUUID() + "@example.test", null, loginAt)).id();
        UUID currentMembershipId = UUID.randomUUID();
        UUID currentTenantId = UUID.randomUUID();
        UUID targetMembershipId = UUID.randomUUID();
        UUID targetTenantId = UUID.randomUUID();
        Sha256Digest presentedDigest = digest(44);
        Sha256Digest successorDigest = digest(45);
        RefreshTokenFamily family = refreshTokenFamilies.create(
                RefreshTokenFamily.start(identityId, RefreshTokenFamilyPurpose.USER_TENANT,
                        currentMembershipId, currentTenantId, loginAt),
                presentedDigest, loginAt);
        String kid = "context-lock-" + UUID.randomUUID();
        SigningKey published = signingKeys.savePublished(SigningKey.publish(
                kid, "kms/" + kid, "modulus-" + kid, "AQAB",
                loginAt.minus(5, ChronoUnit.MINUTES)));
        signingKeys.activate(published.id(), loginAt);
        IssuedAccessToken accessToken = accessToken(refreshAt, kid);
        RefreshRotationTransaction transaction = refreshRotationTransaction();
        CountDownLatch refreshLocked = new CountDownLatch(1);
        CountDownLatch allowRefreshCommit = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RefreshRotationTransaction.Result result = transaction.commit(
                        new RefreshTokenMaterial("presented", presentedDigest),
                        new RefreshTokenMaterial("successor", successorDigest), digest(46),
                        family.contextVersion(), currentMembershipId, currentTenantId,
                        accessToken, refreshAt, null);
                refreshLocked.countDown();
                await(allowRefreshCommit);
                return result;
            }));
            boolean locked = refreshLocked.await(5, TimeUnit.SECONDS);
            if (!locked) {
                refresh.get(1, TimeUnit.SECONDS);
            }
            assertTrue(locked);
            var contextChange = executor.submit(() -> refreshTokenFamilies.switchTenantContext(
                    family.id(), family.contextVersion(), targetMembershipId, targetTenantId));

            allowRefreshCommit.countDown();

            assertEquals(RefreshRotation.Status.ROTATED,
                    refresh.get(5, TimeUnit.SECONDS).status());
            assertEquals(RefreshTokenFamilyContextChange.Status.CHANGED,
                    contextChange.get(5, TimeUnit.SECONDS).status());
        } finally {
            executor.shutdownNow();
        }

        RefreshTokenFamily persisted = refreshTokenFamilies.findById(family.id()).orElseThrow();
        assertEquals(1, persisted.contextVersion());
        assertEquals(targetMembershipId, persisted.membershipId());
        assertEquals(targetTenantId, persisted.tenantId());
        assertEquals(refreshAt, persisted.lastUsedAt());
        assertTrue(accessTokenIssuances.findByJti(accessToken.jti()).isPresent());
        assertNotNull(tokenConsumedAt(presentedDigest));
        assertEquals(1, tokenCount(successorDigest));
    }

    @Test
    void enforcesClientScopeSecretOverlapAndTerminalRevocation() {
        Instant now = Instant.parse("2026-08-20T02:00:00Z");
        Sha256Digest initialSecret = digest(4);
        OAuthClient client = clients.create(OAuthClient.register("worker", Set.of(OAuthScope.RUNTIME_READ), now), initialSecret, now);

        assertTrue(clients.findActiveBySecretDigest(initialSecret, now).isPresent());
        clients.rotate(client.id(), digest(5), now.plusSeconds(1));
        assertThrows(IllegalStateException.class, () -> clients.rotate(client.id(), digest(6), now.plusSeconds(2)));
        assertTrue(clients.findActiveBySecretDigest(initialSecret, now.plus(23, ChronoUnit.HOURS)).isPresent());
        assertFalse(clients.findActiveBySecretDigest(initialSecret, now.plus(25, ChronoUnit.HOURS)).isPresent());

        clients.revoke(client.id(), now.plusSeconds(3));
        assertFalse(clients.findActiveBySecretDigest(digest(5), now.plusSeconds(4)).isPresent());
    }

    @Test
    void persistsFixedReservedClientIdInternalScopesAndSecretDigestOnly() throws SQLException {
        Instant now = Instant.parse("2026-08-21T02:00:00Z");
        UUID clientId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        Sha256Digest digest = ClientSecretDigest.fromPlaintext(secret);
        OAuthClient client = OAuthClient.register(
                        "iam-service", Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ), now)
                .identifiedBy(clientId);

        OAuthClient persisted = clients.createWithId(client, digest, now);
        var state = clients.findBootstrapState(clientId).orElseThrow();

        assertEquals(clientId, persisted.id());
        assertEquals(Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ), state.client().allowedScopes());
        assertTrue(state.exactlyMatches(digest));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT secret_digest::text FROM iam_oauth_client_secrets WHERE client_id = ?")) {
            statement.setObject(1, clientId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertFalse(result.getString(1).contains(secret));
            }
        }
    }

    @Test
    void reservedClientBootstrapIsStrictlyIdempotentAndRejectsDatabaseDrift() {
        List<ReservedServiceClientBootstrapInput> inputs = List.of(
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.IAM,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92"), serviceClientSecret((byte) 21)),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.TENANT_ACCESS,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c93"), serviceClientSecret((byte) 22)),
                new ReservedServiceClientBootstrapInput(
                        ReservedServiceClient.ENTITLEMENT,
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c94"), serviceClientSecret((byte) 23)));

        ReservedServiceClientBootstrapResult initialized = reservedClientBootstrap.bootstrap(inputs);
        ReservedServiceClientBootstrapResult replayed = reservedClientBootstrap.bootstrap(inputs);

        assertTrue(initialized.clients().values().stream()
                .allMatch(value -> value.outcome() == ReservedServiceClientBootstrapResult.Outcome.INITIALIZED));
        assertTrue(replayed.clients().values().stream()
                .allMatch(value -> value.outcome() == ReservedServiceClientBootstrapResult.Outcome.ALREADY_INITIALIZED));
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE iam_oauth_clients SET allowed_scopes = ARRAY['runtime:read'] WHERE id = ?",
                inputs.get(0).clientId());
        assertThrows(ReservedServiceClientBootstrapConflictException.class,
                () -> reservedClientBootstrap.bootstrap(inputs));
    }

    @Test
    void persistsSigningKeyMetadataEnforcesUniquenessAndLifecycle() throws SQLException {
        Instant now = Instant.parse("2026-08-20T03:00:00Z");
        SigningKey first = signingKeys.savePublished(SigningKey.publish("kid-" + UUID.randomUUID(), "kms/key/1", "modulus-1", "AQAB", now));
        assertTrue(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(first.id())));

        assertThrows(IllegalStateException.class, () -> signingKeys.activate(first.id(), now.plus(4, ChronoUnit.MINUTES)));
        signingKeys.activate(first.id(), now.plus(5, ChronoUnit.MINUTES));
        assertEquals(Duration.ofHours(8),
                signingKeys.prepareActiveForIssuance(Duration.ofHours(8)).maxIssuedTokenTtl());
        assertEquals(Duration.ofHours(8),
                signingKeys.prepareActiveForIssuance(Duration.ofMinutes(15)).maxIssuedTokenTtl());
        SigningKey persistedFirst = signingKeys.findActive().orElseThrow();
        assertEquals(first.kid(), persistedFirst.kid());
        assertEquals("kms/key/1", persistedFirst.keyVersionReference());
        assertEquals("modulus-1", persistedFirst.publicJwkModulus());
        assertEquals("AQAB", persistedFirst.publicJwkExponent());

        assertThrows(DataIntegrityViolationException.class, () -> signingKeys.savePublished(SigningKey.publish(
                first.kid(), "kms/key/duplicate", "modulus-duplicate", "AQAB", now.plus(6, ChronoUnit.MINUTES))));
        assertDatabaseRejectsSecondActiveKey(now.plus(6, ChronoUnit.MINUTES));

        SigningKey second = signingKeys.savePublished(SigningKey.publish(
                "kid-" + UUID.randomUUID(), "kms/key/2", "modulus-2", "AQAB", now.plus(6, ChronoUnit.MINUTES)));
        SigningKey active = signingKeys.activate(second.id(), now.plus(11, ChronoUnit.MINUTES));
        assertEquals(SigningKeyStatus.ACTIVE, active.status());
        assertEquals(second.id(), signingKeys.findActive().orElseThrow().id());

        SigningKey replacement = signingKeys.savePublished(SigningKey.publish(
                "kid-" + UUID.randomUUID(), "kms/key/3", "modulus-3", "AQAB", now.plus(6, ChronoUnit.MINUTES)));
        SigningKey revoked = signingKeys.revoke(second.id(), replacement.id(), now.plus(12, ChronoUnit.MINUTES));
        assertEquals(SigningKeyStatus.REVOKED, revoked.status());
        assertEquals(replacement.id(), signingKeys.findActive().orElseThrow().id());
        assertFalse(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(second.id())));

        assertThrows(IllegalStateException.class, () -> signingKeys.retire(first.id(), now.plus(491, ChronoUnit.MINUTES)));
        assertEquals(SigningKeyStatus.RETIRED, signingKeys.retire(first.id(), now.plus(492, ChronoUnit.MINUTES)).status());
        assertFalse(signingKeys.findPublishedVerificationKeys().stream().anyMatch(key -> key.id().equals(first.id())));
    }

    @Test
    void migrationGrantsOnlyRuntimeDmlAndDoesNotCreatePrivateKeyColumns() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertFalse(columnExists(connection, "iam_signing_keys", "private_key"));
            assertFalse(columnExists(connection, "iam_signing_keys", "private_jwk"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute("CREATE TABLE iam_probe (id UUID)"));
            assertThrows(SQLException.class, () -> connection.createStatement().execute(
                    "INSERT INTO iam_oauth_clients (display_name, allowed_scopes, client_status, created_at) "
                            + "VALUES ('invalid', ARRAY['tenant:write'], 'ACTIVE', now())"));
        }
    }

    private RefreshRotationTransaction refreshRotationTransaction() {
        return new RefreshRotationTransaction(
                refreshTokenFamilies,
                org.mockito.Mockito.mock(io.saasforge.iam.domain.session.TenantContextSwitchRepository.class),
                accessTokenIssuances, null, null, null, null, Duration.ofSeconds(10),
                (membershipId, tenantId) -> { });
    }

    private static IssuedAccessToken accessToken(Instant issuedAt) {
        return accessToken(issuedAt, "test-kid");
    }

    private static IssuedAccessToken accessToken(Instant issuedAt, String kid) {
        return new IssuedAccessToken(
                "prepared-access-token", uuidV7(), kid,
                issuedAt, issuedAt.plusSeconds(900), 900);
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0198c9d50f257000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }

    private OffsetDateTime tokenConsumedAt(Sha256Digest digest) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT consumed_at FROM iam_refresh_tokens WHERE token_digest = ?",
                OffsetDateTime.class, digest.value());
    }

    private int tokenCount(Sha256Digest digest) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM iam_refresh_tokens WHERE token_digest = ?",
                Integer.class, digest.value());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发事务超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发事务被中断", exception);
        }
    }

    private int uuidVersion(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT uuid_extract_version(?)")) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static Sha256Digest digest(int value) {
        byte[] digest = new byte[32];
        digest[0] = (byte) value;
        return Sha256Digest.of(digest);
    }

    private static String serviceClientSecret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)")) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private void assertDatabaseRejectsSecondActiveKey(Instant at) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO iam_signing_keys "
                                + "(kid, key_version_reference, public_jwk_modulus, public_jwk_exponent, key_status, published_at, activated_at) "
                                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)")) {
            OffsetDateTime timestamp = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
            statement.setString(1, "direct-active-" + UUID.randomUUID());
            statement.setString(2, "kms/key/direct");
            statement.setString(3, "modulus-direct");
            statement.setString(4, "AQAB");
            statement.setObject(5, timestamp);
            statement.setObject(6, timestamp);

            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    private static String iamJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saasforge", "/iam_db");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(basePackages = "io.saasforge.iam.infrastructure.persistence.mapper", sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = MyBatisIdentityRepository.class)
    static class PersistenceConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(iamJdbcUrl(), "iam_app", "iam-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory iamSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory iamSqlSessionFactory) {
            return new SqlSessionTemplate(iamSqlSessionFactory);
        }

        @Bean
        ReservedServiceClientBootstrapService reservedServiceClientBootstrapService(OAuthClientRepository clients) {
            return new ReservedServiceClientBootstrapService(
                    clients, Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC));
        }
    }
}
