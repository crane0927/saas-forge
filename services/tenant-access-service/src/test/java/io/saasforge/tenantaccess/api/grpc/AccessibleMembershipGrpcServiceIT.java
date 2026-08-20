package io.saasforge.tenantaccess.api.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership;
import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsRequest;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsResponse;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisAccessibleMembershipQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
@SpringJUnitConfig(AccessibleMembershipGrpcServiceIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessibleMembershipGrpcServiceIT {

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
                    MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    static {
        POSTGRES.start();
    }

    @Autowired
    private AccessibleMembershipGrpcService grpcService;

    private Server server;
    private ManagedChannel channel;
    private AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceBlockingStub client;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(tenantAccessJdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void startGrpcServer() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(grpcService)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = AccessibleMembershipQueryServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopGrpcServer() throws Exception {
        channel.shutdownNow();
        server.shutdownNow();
        server.awaitTermination();
    }

    @Test
    void distinguishesZeroAndOneAccessibleMembership() throws SQLException {
        UUID identityId = uuidV7(1000);
        assertEquals(0, list(identityId).getMembershipsCount());

        UUID tenantId = uuidV7(1001);
        UUID membershipId = uuidV7(1002);
        insertTenantAndMembership(identityId, tenantId, membershipId, "唯一租户", "ACTIVE", "ENABLED", null);

        ListAccessibleMembershipsResponse response = list(identityId);
        assertEquals(1, response.getMembershipsCount());
        assertEquals(membershipId.toString(), response.getMemberships(0).getMembershipId());
        assertEquals(tenantId.toString(), response.getMemberships(0).getTenantId());
        assertEquals("唯一租户", response.getMemberships(0).getTenantDisplayName());
    }

    @Test
    void returnsMultipleMembershipsInStableTenantNameAndMembershipIdOrder() throws SQLException {
        UUID identityId = uuidV7(2000);
        insertTenantAndMembership(identityId, uuidV7(2001), uuidV7(2005), "Beta", "ACTIVE", "ENABLED", null);
        insertTenantAndMembership(identityId, uuidV7(2002), uuidV7(2004), "Alpha", "ACTIVE", "ENABLED", null);
        insertTenantAndMembership(identityId, uuidV7(2003), uuidV7(2006), "Alpha", "ACTIVE", "ENABLED", null);

        List<AccessibleMembership> memberships = list(identityId).getMembershipsList();
        assertIterableEquals(
                List.of(uuidV7(2004).toString(), uuidV7(2006).toString(), uuidV7(2005).toString()),
                memberships.stream().map(AccessibleMembership::getMembershipId).toList());
        assertIterableEquals(
                List.of("Alpha", "Alpha", "Beta"),
                memberships.stream().map(AccessibleMembership::getTenantDisplayName).toList());
    }

    @Test
    void readsOnlyOneHundredAndOneMembershipsToSignalOverflow() throws SQLException {
        UUID identityId = uuidV7(3000);
        for (int index = 0; index < 102; index++) {
            insertTenantAndMembership(
                    identityId,
                    uuidV7(3100 + index),
                    uuidV7(3300 + index),
                    "Tenant " + String.format("%03d", index),
                    "ACTIVE",
                    "ENABLED",
                    null);
        }

        ListAccessibleMembershipsResponse response = list(identityId);
        assertEquals(101, response.getMembershipsCount());
        assertEquals("Tenant 000", response.getMemberships(0).getTenantDisplayName());
        assertEquals("Tenant 100", response.getMemberships(100).getTenantDisplayName());
    }

    @Test
    void reflectsMembershipAndTenantAccessStateChanges() throws SQLException {
        UUID identityId = uuidV7(4000);
        UUID tenantId = uuidV7(4001);
        UUID membershipId = uuidV7(4002);
        insertTenantAndMembership(identityId, tenantId, membershipId, "状态租户", "ACTIVE", "ENABLED", null);
        assertEquals(1, list(identityId).getMembershipsCount());

        updateMembershipStatus(membershipId, "DISABLED");
        assertEquals(0, list(identityId).getMembershipsCount());

        updateMembershipStatus(membershipId, "ENABLED");
        updateTenantState(tenantId, "SUSPENDED", null);
        assertEquals(0, list(identityId).getMembershipsCount());

        updateTenantState(tenantId, "ACTIVE", Instant.now().minusSeconds(1));
        assertEquals(0, list(identityId).getMembershipsCount());
    }

    @Test
    void rejectsNonCanonicalOrNonV7IdentityIds() {
        StatusRuntimeException randomUuid = org.junit.jupiter.api.Assertions.assertThrows(
                StatusRuntimeException.class,
                () -> client.listAccessibleMemberships(ListAccessibleMembershipsRequest.newBuilder()
                        .setIdentityId(UUID.randomUUID().toString())
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, randomUuid.getStatus().getCode());

        StatusRuntimeException uppercase = org.junit.jupiter.api.Assertions.assertThrows(
                StatusRuntimeException.class,
                () -> client.listAccessibleMemberships(ListAccessibleMembershipsRequest.newBuilder()
                        .setIdentityId(uuidV7(5000).toString().toUpperCase())
                        .build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, uppercase.getStatus().getCode());
    }

    private ListAccessibleMembershipsResponse list(UUID identityId) {
        return client.listAccessibleMemberships(ListAccessibleMembershipsRequest.newBuilder()
                .setIdentityId(identityId.toString())
                .build());
    }

    private static void insertTenantAndMembership(
            UUID identityId,
            UUID tenantId,
            UUID membershipId,
            String displayName,
            String tenantStatus,
            String membershipStatus,
            Instant expiresAt) throws SQLException {
        try (Connection connection = migratorConnection()) {
            try (PreparedStatement tenant = connection.prepareStatement(
                    "INSERT INTO tenants (id, display_name, tenant_status, expires_at) VALUES (?, ?, ?, ?)")) {
                tenant.setObject(1, tenantId);
                tenant.setString(2, displayName);
                tenant.setString(3, tenantStatus);
                tenant.setObject(4, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
                tenant.executeUpdate();
            }
            try (PreparedStatement membership = connection.prepareStatement(
                    "INSERT INTO memberships (id, tenant_id, identity_id, membership_status) VALUES (?, ?, ?, ?)")) {
                membership.setObject(1, membershipId);
                membership.setObject(2, tenantId);
                membership.setObject(3, identityId);
                membership.setString(4, membershipStatus);
                membership.executeUpdate();
            }
        }
    }

    private static void updateMembershipStatus(UUID membershipId, String status) throws SQLException {
        try (Connection connection = migratorConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE memberships SET membership_status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, membershipId);
            statement.executeUpdate();
        }
    }

    private static void updateTenantState(UUID tenantId, String status, Instant expiresAt) throws SQLException {
        try (Connection connection = migratorConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE tenants SET tenant_status = ?, expires_at = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            statement.setObject(3, tenantId);
            statement.executeUpdate();
        }
    }

    private static Connection migratorConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                tenantAccessJdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password");
    }

    private static String tenantAccessJdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/tenant_access_db";
    }

    private static UUID uuidV7(long sequence) {
        return UUID.fromString("019535d9-" + String.format("%04x", sequence)
                + "-7000-8000-" + String.format("%012x", sequence));
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
    @MapperScan(
            basePackages = "io.saasforge.tenantaccess.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "tenantAccessSqlSessionFactory")
    @ComponentScan(basePackageClasses = {MyBatisAccessibleMembershipQuery.class, AccessibleMembershipGrpcService.class})
    static class PersistenceConfiguration {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    tenantAccessJdbcUrl(), "tenant_access_app", "tenant-access-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory tenantAccessSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.tenantaccess.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory tenantAccessSqlSessionFactory) {
            return new SqlSessionTemplate(tenantAccessSqlSessionFactory);
        }
    }
}
