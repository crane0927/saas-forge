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
    private static final DatabaseAccount AUDIT = DATABASES.get(3);

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
        assertThrows(SQLException.class,
                () -> execute(TENANT_ACCESS, TENANT_ACCESS.appRole(), TENANT_ACCESS.appPassword(),
                        "SET ROLE tenant_access_migrator"));
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
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "DROP TABLE IF EXISTS tenant_boundary_fixture");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "CREATE TABLE tenant_boundary_fixture (id uuid PRIMARY KEY DEFAULT uuidv7(), tenant_id uuid NOT NULL, value text NOT NULL)");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "ALTER TABLE tenant_boundary_fixture ENABLE ROW LEVEL SECURITY");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "ALTER TABLE tenant_boundary_fixture FORCE ROW LEVEL SECURITY");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "CREATE POLICY tenant_runtime_access ON tenant_boundary_fixture FOR ALL TO tenant_access_app "
                        + "USING (tenant_id = current_setting('app.tenant_id', true)::uuid) "
                        + "WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid)");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "CREATE POLICY tenant_migration_access ON tenant_boundary_fixture FOR ALL TO tenant_access_migrator "
                        + "USING (true) WITH CHECK (true)");
        execute(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_boundary_fixture TO tenant_access_app");

        UUID tenantA = UUID.fromString("019535d9-3df7-79fb-b466-fa907fa17f9e");
        UUID tenantB = UUID.fromString("019535d9-3df8-79fb-b466-fa907fa17f9e");
        try (Connection migrator = connection(
                TENANT_ACCESS.databaseName(), TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword());
                PreparedStatement insert = migrator.prepareStatement(
                        "INSERT INTO tenant_boundary_fixture (tenant_id, value) VALUES (?, ?)")) {
            insert.setObject(1, tenantA);
            insert.setString(2, "tenant-a");
            insert.executeUpdate();
            insert.setObject(1, tenantB);
            insert.setString(2, "tenant-b");
            insert.executeUpdate();
        }

        assertEquals(0, tenantRowCount(null));
        assertEquals(1, tenantRowCount(tenantA));
        assertEquals(1, tenantRowCount(tenantB));
        assertEquals(2, queryLong(TENANT_ACCESS, TENANT_ACCESS.migratorRole(), TENANT_ACCESS.migratorPassword(),
                "SELECT count(*) FROM tenant_boundary_fixture"));
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

    private static int tenantRowCount(UUID tenantId) throws SQLException {
        try (Connection connection = connection(
                TENANT_ACCESS.databaseName(), TENANT_ACCESS.appRole(), TENANT_ACCESS.appPassword())) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT set_config('app.tenant_id', ?, true)")) {
                        statement.setString(1, tenantId.toString());
                        statement.execute();
                    }
                }
                try (Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT count(*) FROM tenant_boundary_fixture")) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
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
