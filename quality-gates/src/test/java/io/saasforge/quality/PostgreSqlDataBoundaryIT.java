package io.saasforge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class PostgreSqlDataBoundaryIT {

    private static final String ADMIN_USER = "saasforge_admin";
    private static final String ADMIN_PASSWORD = "admin-password";
    private static final Path REPOSITORY = Path.of(System.getProperty("repositoryRoot"));
    private static final List<DatabaseAccount> DATABASES = List.of(
            new DatabaseAccount("iam-service", "iam_db", "iam_migrator", "iam-migrator-password", "iam_app", "iam-app-password"),
            new DatabaseAccount(
                    "tenant-access-service",
                    "tenant_access_db",
                    "tenant_access_migrator",
                    "tenant-access-migrator-password",
                    "tenant_access_app",
                    "tenant-access-app-password"),
            new DatabaseAccount(
                    "entitlement-service",
                    "entitlement_db",
                    "entitlement_migrator",
                    "entitlement-migrator-password",
                    "entitlement_app",
                    "entitlement-app-password"),
            new DatabaseAccount(
                    "audit-service",
                    "audit_db",
                    "audit_migrator",
                    "audit-migrator-password",
                    "audit_app",
                    "audit-app-password"));

    private static final DatabaseAccount IAM = DATABASES.get(0);
    private static final DatabaseAccount TENANT_ACCESS = DATABASES.get(1);
    private static final DatabaseAccount ENTITLEMENT = DATABASES.get(2);
    private static final DatabaseAccount AUDIT = DATABASES.get(3);
    private static final List<DatabaseAccount> TENANT_SCOPED_DATABASES = List.of(TENANT_ACCESS, ENTITLEMENT);
    private static final String TENANT_BOUNDARY_FIXTURE = "tenant_boundary_fixture";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("saasforge")
            .withUsername(ADMIN_USER)
            .withPassword(ADMIN_PASSWORD)
            .withEnv("IAM_MIGRATOR_PASSWORD", IAM.migratorPassword())
            .withEnv("IAM_APP_PASSWORD", IAM.appPassword())
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", TENANT_ACCESS.migratorPassword())
            .withEnv("TENANT_ACCESS_APP_PASSWORD", TENANT_ACCESS.appPassword())
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", DATABASES.get(2).migratorPassword())
            .withEnv("ENTITLEMENT_APP_PASSWORD", DATABASES.get(2).appPassword())
            .withEnv("AUDIT_MIGRATOR_PASSWORD", AUDIT.migratorPassword())
            .withEnv("AUDIT_APP_PASSWORD", AUDIT.appPassword())
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REPOSITORY.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @BeforeAll
    static void migrateServiceDatabases() {
        for (DatabaseAccount database : DATABASES) {
            Flyway.configure()
                    .dataSource(databaseUrl(database.databaseName()), database.migratorRole(), database.migratorPassword())
                    .locations("filesystem:" + REPOSITORY.resolve("services")
                            .resolve(database.serviceModule())
                            .resolve("src/main/resources/db/migration"))
                    .load()
                    .migrate();
        }
    }

    @Test
    void bootstrapCreatesIsolatedDatabaseAccountsAndMigrationChains() throws SQLException {
        try (Connection admin = adminConnection()) {
            for (DatabaseAccount database : DATABASES) {
                assertTrue(databaseExists(admin, database.databaseName()));
                assertFalse(roleCanBypassRls(admin, database.appRole()));
                assertFalse(roleCanInherit(admin, database.appRole()));
                assertFalse(roleCanBypassRls(admin, database.migratorRole()));
                assertFalse(roleHasMembership(admin, database.appRole(), database.migratorRole()));
                assertEquals(database.migratorRole(), tableOwner(database, "flyway_schema_history"));
                assertEquals(1, migrationCount(database));
            }
        }

        for (DatabaseAccount database : DATABASES) {
            try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
                assertTrue(connection.isValid(5));
            }
        }

        assertThrows(SQLException.class,
                () -> connection(TENANT_ACCESS.databaseName(), IAM.appRole(), IAM.appPassword()));
        assertThrows(SQLException.class,
                () -> execute(IAM, IAM.appRole(), IAM.appPassword(), "CREATE TABLE runtime_object_probe (id uuid)"));
        for (DatabaseAccount database : TENANT_SCOPED_DATABASES) {
            assertThrows(SQLException.class,
                    () -> execute(database, database.appRole(), database.appPassword(),
                            "SET ROLE " + database.migratorRole()));
        }
        assertThrows(SQLException.class,
                () -> execute(IAM, IAM.appRole(), IAM.appPassword(), "CREATE EXTENSION dblink"));
    }

    @Test
    void databaseGeneratesUuidV7ForRuntimeInserts() throws SQLException {
        execute(IAM, IAM.migratorRole(), IAM.migratorPassword(), "DROP TABLE IF EXISTS uuidv7_probe");
        execute(IAM, IAM.migratorRole(), IAM.migratorPassword(),
                "CREATE TABLE uuidv7_probe (id uuid PRIMARY KEY DEFAULT uuidv7())");
        execute(IAM, IAM.migratorRole(), IAM.migratorPassword(), "GRANT SELECT, INSERT ON uuidv7_probe TO iam_app");

        UUID generatedId;
        try (Connection connection = connection(IAM.databaseName(), IAM.appRole(), IAM.appPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("INSERT INTO uuidv7_probe DEFAULT VALUES RETURNING id")) {
            assertTrue(result.next());
            generatedId = result.getObject(1, UUID.class);
        }

        assertNotNull(generatedId);
        try (Connection connection = connection(IAM.databaseName(), IAM.migratorRole(), IAM.migratorPassword());
                PreparedStatement statement = connection.prepareStatement("SELECT uuid_extract_version(?)")) {
            statement.setObject(1, generatedId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(7, result.getInt(1));
            }
        }
    }

    @Test
    void tenantRuntimeAccountIsRestrictedByTenantContextWhileMigratorCanBackfill() throws SQLException {
        UUID tenantA = UUID.fromString("019535d9-3df7-79fb-b466-fa907fa17f9e");
        UUID tenantB = UUID.fromString("019535d9-3df8-79fb-b466-fa907fa17f9e");
        for (DatabaseAccount database : TENANT_SCOPED_DATABASES) {
            createTenantBoundaryFixture(database);
            seedTenantBoundaryFixture(database, tenantA, tenantB);

            assertTenantFixtureMetadata(database);
            assertEquals(0, tenantRowCount(database, null));
            assertEquals(1, tenantRowCount(database, tenantA));
            assertEquals(1, tenantRowCount(database, tenantB));
            assertOwnTenantDmlWorks(database, tenantA);
            assertForeignTenantDmlIsBlocked(database, tenantA, tenantB);
            assertMissingAndInvalidTenantContextAreRejected(database, tenantA);
            assertTenantContextDoesNotLeakAfterCommit(database, tenantA);
            assertMigratorCanBackfillAcrossTenants(database, tenantA, tenantB);
        }
    }

    @Test
    void auditRuntimeAccountCanOnlyAppendAuditRecords() throws SQLException {
        execute(AUDIT, AUDIT.migratorRole(), AUDIT.migratorPassword(), "DROP TABLE IF EXISTS audit_records");
        execute(AUDIT, AUDIT.migratorRole(), AUDIT.migratorPassword(),
                "CREATE TABLE audit_records (id uuid PRIMARY KEY DEFAULT uuidv7(), action text NOT NULL)");
        execute(AUDIT, AUDIT.migratorRole(), AUDIT.migratorPassword(), "GRANT SELECT, INSERT ON audit_records TO audit_app");

        execute(AUDIT, AUDIT.appRole(), AUDIT.appPassword(), "INSERT INTO audit_records (action) VALUES ('created')");
        assertEquals(1, queryLong(AUDIT, AUDIT.appRole(), AUDIT.appPassword(), "SELECT count(*) FROM audit_records"));
        assertThrows(SQLException.class,
                () -> execute(AUDIT, AUDIT.appRole(), AUDIT.appPassword(), "UPDATE audit_records SET action = 'changed'"));
        assertThrows(SQLException.class,
                () -> execute(AUDIT, AUDIT.appRole(), AUDIT.appPassword(), "DELETE FROM audit_records"));
        assertThrows(SQLException.class,
                () -> execute(AUDIT, AUDIT.appRole(), AUDIT.appPassword(), "TRUNCATE audit_records"));
    }

    private static void createTenantBoundaryFixture(DatabaseAccount database) throws SQLException {
        execute(database, database.migratorRole(), database.migratorPassword(),
                "DROP TABLE IF EXISTS " + TENANT_BOUNDARY_FIXTURE);
        execute(database, database.migratorRole(), database.migratorPassword(),
                "CREATE TABLE " + TENANT_BOUNDARY_FIXTURE
                        + " (id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, value text NOT NULL)");
        execute(database, database.migratorRole(), database.migratorPassword(),
                "ALTER TABLE " + TENANT_BOUNDARY_FIXTURE + " ENABLE ROW LEVEL SECURITY");
        execute(database, database.migratorRole(), database.migratorPassword(),
                "ALTER TABLE " + TENANT_BOUNDARY_FIXTURE + " FORCE ROW LEVEL SECURITY");
        execute(database, database.migratorRole(), database.migratorPassword(),
                "CREATE POLICY tenant_runtime_access ON " + TENANT_BOUNDARY_FIXTURE + " FOR ALL TO " + database.appRole()
                        + " USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) "
                        + "WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
        execute(database, database.migratorRole(), database.migratorPassword(),
                "CREATE POLICY tenant_migration_access ON " + TENANT_BOUNDARY_FIXTURE + " FOR ALL TO "
                        + database.migratorRole() + " USING (true) WITH CHECK (true)");
        execute(database, database.migratorRole(), database.migratorPassword(),
                "GRANT SELECT, INSERT, UPDATE, DELETE ON " + TENANT_BOUNDARY_FIXTURE + " TO " + database.appRole());
    }

    private static void seedTenantBoundaryFixture(DatabaseAccount database, UUID tenantA, UUID tenantB) throws SQLException {
        try (Connection migrator = connection(database.databaseName(), database.migratorRole(), database.migratorPassword());
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO " + TENANT_BOUNDARY_FIXTURE + " (tenant_id, value) VALUES (?, ?)")) {
            insert.setObject(1, tenantA);
            insert.setString(2, "tenant-a");
            insert.executeUpdate();
            insert.setObject(1, tenantB);
            insert.setString(2, "tenant-b");
            insert.executeUpdate();
        }
    }

    private static void assertTenantFixtureMetadata(DatabaseAccount database) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.migratorRole(), database.migratorPassword())) {
            assertTrue(tableUsesForcedRowLevelSecurity(connection));
            assertTrue(tenantIdIsNotNull(connection));
            assertEquals(List.of(database.appRole()), policyRoles(connection, "tenant_runtime_access"));
            assertEquals(List.of(database.migratorRole()), policyRoles(connection, "tenant_migration_access"));
        }
    }

    private static void assertOwnTenantDmlWorks(DatabaseAccount database, UUID tenantId) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantId.toString());
                assertEquals(1, tenantRowCount(connection));
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + TENANT_BOUNDARY_FIXTURE + " (tenant_id, value) VALUES (?, ?)")) {
                    insert.setObject(1, tenantId);
                    insert.setString(2, "tenant-a-created");
                    assertEquals(1, insert.executeUpdate());
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TENANT_BOUNDARY_FIXTURE + " SET value = ? WHERE value = ?")) {
                    update.setString(1, "tenant-a-updated");
                    update.setString(2, "tenant-a-created");
                    assertEquals(1, update.executeUpdate());
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + TENANT_BOUNDARY_FIXTURE + " WHERE value = ?")) {
                    delete.setString(1, "tenant-a-updated");
                    assertEquals(1, delete.executeUpdate());
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertForeignTenantDmlIsBlocked(
            DatabaseAccount database,
            UUID tenantId,
            UUID foreignTenantId) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantId.toString());
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TENANT_BOUNDARY_FIXTURE + " SET value = 'foreign-update' WHERE tenant_id = ?")) {
                    update.setObject(1, foreignTenantId);
                    assertEquals(0, update.executeUpdate());
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + TENANT_BOUNDARY_FIXTURE + " WHERE tenant_id = ?")) {
                    delete.setObject(1, foreignTenantId);
                    assertEquals(0, delete.executeUpdate());
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
        }

        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantId.toString());
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + TENANT_BOUNDARY_FIXTURE + " (tenant_id, value) VALUES (?, ?)")) {
                    insert.setObject(1, foreignTenantId);
                    insert.setString(2, "foreign-insert");
                    assertThrows(SQLException.class, insert::executeUpdate);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertMissingAndInvalidTenantContextAreRejected(DatabaseAccount database, UUID tenantId) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                assertEquals(0, tenantRowCount(connection));
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + TENANT_BOUNDARY_FIXTURE + " (tenant_id, value) VALUES (?, ?)")) {
                    insert.setObject(1, tenantId);
                    insert.setString(2, "missing-context");
                    assertThrows(SQLException.class, insert::executeUpdate);
                }
            } finally {
                connection.rollback();
            }
        }

        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, "not-a-uuid");
                assertThrows(SQLException.class, () -> tenantRowCount(connection));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertTenantContextDoesNotLeakAfterCommit(DatabaseAccount database, UUID tenantId) throws SQLException {
        try (Connection connection = connection(
                database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                setTenantContext(connection, tenantId.toString());
                assertEquals(1, tenantRowCount(connection));
                connection.commit();
                assertEquals(0, tenantRowCount(connection));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertMigratorCanBackfillAcrossTenants(
            DatabaseAccount database,
            UUID tenantA,
            UUID tenantB) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.migratorRole(), database.migratorPassword());
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TENANT_BOUNDARY_FIXTURE + " SET value = 'backfilled' WHERE tenant_id IN (?, ?)")) {
            update.setObject(1, tenantA);
            update.setObject(2, tenantB);
            assertEquals(2, update.executeUpdate());
        }
        assertEquals(2, queryLong(database, database.migratorRole(), database.migratorPassword(),
                "SELECT count(*) FROM " + TENANT_BOUNDARY_FIXTURE + " WHERE value = 'backfilled'"));
    }

    private static int tenantRowCount(DatabaseAccount database, UUID tenantId) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.appRole(), database.appPassword())) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    setTenantContext(connection, tenantId.toString());
                }
                return tenantRowCount(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private static int tenantRowCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM " + TENANT_BOUNDARY_FIXTURE)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void setTenantContext(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }

    private static long migrationCount(DatabaseAccount database) throws SQLException {
        return queryLong(database, database.migratorRole(), database.migratorPassword(),
                "SELECT count(*) FROM flyway_schema_history WHERE success");
    }

    private static String tableOwner(DatabaseAccount database, String tableName) throws SQLException {
        try (Connection connection = connection(database.databaseName(), database.migratorRole(), database.migratorPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?")) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static boolean databaseExists(Connection connection, String databaseName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = ?)")) {
            statement.setString(1, databaseName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean roleCanBypassRls(Connection connection, String roleName) throws SQLException {
        return roleAttribute(connection, roleName, "rolbypassrls");
    }

    private static boolean roleCanInherit(Connection connection, String roleName) throws SQLException {
        return roleAttribute(connection, roleName, "rolinherit");
    }

    private static boolean roleHasMembership(Connection connection, String memberRole, String targetRole) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_has_role(?, ?, 'member')")) {
            statement.setString(1, memberRole);
            statement.setString(2, targetRole);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean tableUsesForcedRowLevelSecurity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass")) {
            statement.setString(1, TENANT_BOUNDARY_FIXTURE);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static boolean tenantIdIsNotNull(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT is_nullable = 'NO' FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'")) {
            statement.setString(1, TENANT_BOUNDARY_FIXTURE);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static List<String> policyRoles(Connection connection, String policyName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT array_to_string(roles, ',') FROM pg_policies WHERE schemaname = 'public' AND tablename = ? "
                        + "AND policyname = ?")) {
            statement.setString(1, TENANT_BOUNDARY_FIXTURE);
            statement.setString(2, policyName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return List.of(result.getString(1).split(","));
            }
        }
    }

    private static boolean roleAttribute(Connection connection, String roleName, String attribute) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + attribute + " FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, roleName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static long queryLong(DatabaseAccount database, String username, String password, String sql) throws SQLException {
        try (Connection connection = connection(database.databaseName(), username, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void execute(DatabaseAccount database, String username, String password, String sql) throws SQLException {
        try (Connection connection = connection(database.databaseName(), username, password);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return connection("saasforge", ADMIN_USER, ADMIN_PASSWORD);
    }

    private static Connection connection(String databaseName, String username, String password) throws SQLException {
        return DriverManager.getConnection(databaseUrl(databaseName), username, password);
    }

    private static String databaseUrl(String databaseName) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + databaseName;
    }

    private record DatabaseAccount(
            String serviceModule,
            String databaseName,
            String migratorRole,
            String migratorPassword,
            String appRole,
            String appPassword) {
    }
}
