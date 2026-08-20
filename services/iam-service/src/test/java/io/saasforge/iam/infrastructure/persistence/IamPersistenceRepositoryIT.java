package io.saasforge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.DuplicateIdentityEmailException;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.session.RefreshTokenConsumption;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
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
    private OAuthClientRepository clients;

    @Autowired
    private SigningKeyRepository signingKeys;

    @Autowired
    private DataSource dataSource;

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
    void persistsSigningKeyMetadataEnforcesUniquenessAndLifecycle() throws SQLException {
        Instant now = Instant.parse("2026-08-20T03:00:00Z");
        SigningKey first = signingKeys.savePublished(SigningKey.publish("kid-" + UUID.randomUUID(), "kms/key/1", "modulus-1", "AQAB", now));

        assertThrows(IllegalStateException.class, () -> signingKeys.activate(first.id(), now.plus(4, ChronoUnit.MINUTES)));
        signingKeys.activate(first.id(), now.plus(5, ChronoUnit.MINUTES));
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

        SigningKey revoked = signingKeys.revoke(second.id(), now.plus(12, ChronoUnit.MINUTES));
        assertEquals(SigningKeyStatus.REVOKED, revoked.status());
        assertTrue(signingKeys.findActive().isEmpty());

        assertThrows(IllegalStateException.class, () -> signingKeys.retire(first.id(), now.plus(40, ChronoUnit.MINUTES)));
        assertEquals(SigningKeyStatus.RETIRED, signingKeys.retire(first.id(), now.plus(41, ChronoUnit.MINUTES)).status());
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
    }
}
