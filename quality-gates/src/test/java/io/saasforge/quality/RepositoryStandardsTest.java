package io.saasforge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

class RepositoryStandardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of(System.getProperty("repositoryRoot"));
    private static final Set<String> SERVICE_ARTIFACTS = Set.of(
            "iam-service", "tenant-access-service", "entitlement-service", "audit-service");
    private static final Map<String, String> SERVICE_PACKAGES = Map.of(
            "iam-service", "io.saasforge.iam",
            "tenant-access-service", "io.saasforge.tenantaccess",
            "entitlement-service", "io.saasforge.entitlement",
            "audit-service", "io.saasforge.audit");
    private static final Pattern ANNOTATED_SQL = Pattern.compile(
            "@(Select|Insert|Update|Delete)(Provider)?\\b");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
            "V([1-9][0-9]*(?:\\.[0-9]+)*)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");
    private static final Pattern REPEATABLE_MIGRATION = Pattern.compile(
            "R__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");
    private static final Pattern REPEATABLE_FORBIDDEN_SQL = Pattern.compile(
            "(?is)\\b(create|alter|drop)\\s+table\\b|\\b(insert|update|delete)\\s+(?:into|from)?\\s*");
    private static final Pattern LOG_EVENT = Pattern.compile(
            "^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern MAPPER_NAMESPACE = Pattern.compile(
            "<mapper\\s+[^>]*namespace=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAPPER_STATEMENT = Pattern.compile(
            "<(?:select|insert|update|delete)\\b[^>]*\\bid=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAPPER_METHOD = Pattern.compile(
            "(?m)^\\s*(?:[A-Za-z0-9_$.<>?, \\[\\]]+\\s+)([A-Za-z][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*;");

    @Test
    void redisRegistryIsCompleteAndConsistent() throws Exception {
        Path schemaPath = REPOSITORY.resolve("contracts/redis/registry.schema.json");
        JsonNode schema = readJson(schemaPath);
        Set<String> requiredEntryFields = textSet(schema.at("/$defs/entry/required"));
        assertFalse(requiredEntryFields.isEmpty(), "Redis Registry Schema 必须声明必填字段");
        Pattern idPattern = Pattern.compile(schema.at("/$defs/entry/properties/id/pattern").asText());
        Pattern keyPatternRule = Pattern.compile(schema.at("/$defs/entry/properties/keyPattern/pattern").asText());
        Set<String> valueFormats = textSet(schema.at("/$defs/entry/properties/valueFormat/enum"));
        Set<String> failurePolicies = textSet(schema.at("/$defs/entry/properties/failurePolicy/enum"));
        Set<String> ttlModes = textSet(schema.at("/$defs/entry/properties/ttl/properties/mode/enum"));

        Set<String> ids = new HashSet<>();
        Set<String> keyPatterns = new HashSet<>();
        List<Path> registryFiles = filesUnder(REPOSITORY.resolve("contracts/redis/registry"), ".json");
        assertFalse(registryFiles.isEmpty(), "至少需要一个 Redis Registry");

        for (Path registryFile : registryFiles) {
            JsonNode registry = readJson(registryFile);
            assertEquals(1, registry.path("registryVersion").asInt(), registryFile + " registryVersion 必须为 1");
            String owner = requiredText(registry, "owner", registryFile);
            assertTrue(registry.path("entries").isArray(), registryFile + " entries 必须是数组");

            for (JsonNode entry : registry.path("entries")) {
                for (String field : requiredEntryFields) {
                    assertTrue(entry.has(field), registryFile + " 的 Key 登记缺少字段 " + field);
                }
                String id = requiredText(entry, "id", registryFile);
                String writer = requiredText(entry, "writer", registryFile);
                String keyPattern = requiredText(entry, "keyPattern", registryFile);
                assertTrue(entry.path("schemaVersion").isIntegralNumber(), id + " 的 schemaVersion 必须是整数");
                int schemaVersion = entry.path("schemaVersion").asInt();

                assertEquals(owner, writer, id + " 的 writer 必须等于 Registry owner");
                assertTrue(idPattern.matcher(id).matches(), "Redis Registry ID 不合法: " + id);
                assertTrue(ids.add(id), "Redis Registry ID 重复: " + id);
                assertTrue(keyPatternRule.matcher(keyPattern).matches(), id + " 的 Key 模式不合法");
                assertTrue(keyPatterns.add(keyPattern), "Redis Key 模式重复: " + keyPattern);
                assertTrue(id.endsWith(".v" + schemaVersion), id + " 的 ID 版本与 schemaVersion 不一致");
                assertTrue(keyPattern.contains(":v" + schemaVersion + ":"),
                        id + " 的 Key 版本与 schemaVersion 不一致");
                assertTrue(keyPattern.startsWith("sf:<environment>:" + owner + ":"),
                        id + " 的 Key 命名空间必须与 writer 一致");
                assertTrue(entry.path("readers").isArray() && !entry.path("readers").isEmpty(),
                        id + " 必须登记读取者");
                textSet(entry.path("readers"));
                assertTrue(valueFormats.contains(entry.path("valueFormat").asText()), id + " 的 valueFormat 不合法");
                assertTrue(failurePolicies.contains(entry.path("failurePolicy").asText()),
                        id + " 的 failurePolicy 不合法");
                assertTrue(ttlModes.contains(entry.at("/ttl/mode").asText()), id + " 的 TTL mode 不合法");
                assertTrue(entry.path("containsRawSensitiveData").isBoolean(),
                        id + " 的 containsRawSensitiveData 必须是布尔值");
                assertFalse(entry.path("containsRawSensitiveData").asBoolean(),
                        id + " 不得包含原始敏感数据");
                assertFalse(requiredText(entry, "maxCardinality", registryFile).isBlank(),
                        id + " 必须说明最大基数");
                assertFalse(requiredText(entry.path("ttl"), "expression", registryFile).isBlank(),
                        id + " 必须说明 TTL");
                String lower = (id + " " + entry.path("purpose").asText()).toLowerCase();
                assertFalse(lower.contains("permission") || lower.contains("feature") || lower.contains("quota"),
                        id + " 不得把 Permission、Feature 或 Quota 作为平台 Redis 用途");
            }
        }
    }

    @Test
    void loggingSchemaAndPolicyStayAligned() throws Exception {
        JsonNode schema = readJson(REPOSITORY.resolve("contracts/logging/application-log.schema.json"));
        JsonNode policy = readJson(REPOSITORY.resolve("contracts/logging/policy.json"));

        Set<String> required = textSet(schema.path("required"));
        assertEquals(Set.of("timestamp", "level", "service", "environment", "event", "message", "schemaVersion"),
                required, "日志基础必填字段发生变化时必须显式评审");
        assertFalse(schema.path("additionalProperties").asBoolean(true), "日志顶层必须使用字段白名单");
        assertEquals(policy.path("schemaVersion").asInt(), schema.at("/properties/schemaVersion/const").asInt(),
                "日志策略与 Schema 版本必须一致");

        Set<String> schemaFields = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaFields::add);
        assertEquals(schemaFields, textSet(policy.path("topLevelFieldWhitelist")),
                "日志策略白名单必须与 Schema 顶层字段一致");

        Set<String> sensitivePatterns = textSet(policy.path("sensitiveFieldPatterns"));
        assertTrue(sensitivePatterns.containsAll(Set.of(
                "authorization", "clientsecret", "cookie", "email", "password", "privatekey", "secret", "token")),
                "日志策略缺少关键敏感字段模式");
        assertFalse(policy.at("/production/debugEnabledByDefault").asBoolean(true),
                "生产环境不得默认启用 DEBUG");
        assertFalse(policy.at("/production/traceEnabledByDefault").asBoolean(true),
                "生产环境不得默认启用 TRACE");
        assertTrue(policy.at("/production/traceConsistentSampling").asBoolean(false),
                "同一 Trace 必须保持一致采样");
        assertEquals(Set.of("ERROR", "WARN"), textSet(policy.at("/sampling/neverSampleLevels")),
                "ERROR 与 WARN 不得采样丢弃");
        assertTrue(textSet(policy.at("/sampling/neverSampleEventPrefixes")).contains("security."),
                "安全事件不得采样丢弃");

        Set<String> retentionClasses = textSet(policy.path("retentionClasses"));
        Set<String> events = new HashSet<>();
        for (JsonNode eventPolicy : policy.path("eventPolicies")) {
            String event = requiredText(eventPolicy, "event", Path.of("contracts/logging/policy.json"));
            double sampleRate = eventPolicy.path("sampleRate").asDouble(-1);
            String minimumLevel = requiredText(eventPolicy, "minimumLevel", Path.of("contracts/logging/policy.json"));
            assertTrue(LOG_EVENT.matcher(event).matches(), "日志事件名不合法: " + event);
            assertTrue(events.add(event), "日志事件策略重复: " + event);
            assertTrue(sampleRate >= 0 && sampleRate <= 1, event + " 的采样率必须在 0 到 1 之间");
            assertTrue(retentionClasses.contains(eventPolicy.path("retentionClass").asText()),
                    event + " 使用了未登记的保留类别");
            if (minimumLevel.equals("ERROR") || minimumLevel.equals("WARN") || event.startsWith("security.")) {
                assertEquals(1.0, sampleRate, 0.0, event + " 不得采样丢弃");
            }
        }
    }

    @Test
    void runtimeSqlOnlyLivesInMapperXml() throws Exception {
        for (Path root : List.of(
                REPOSITORY.resolve("services"),
                REPOSITORY.resolve("gateway"),
                REPOSITORY.resolve("sdk"))) {
            for (Path javaFile : filesUnder(root, ".java")) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                assertFalse(ANNOTATED_SQL.matcher(source).find(),
                        javaFile + " 使用了注解或 Provider SQL；运行时 SQL 必须维护在 Mapper XML");
            }
        }
    }

    @Test
    void persistenceArtifactsStayInsideOwningService() throws Exception {
        Map<String, Set<String>> migrationVersions = new HashMap<>();
        for (Path file : repositoryFiles()) {
            Path relative = REPOSITORY.relativize(file);
            String normalized = relative.toString().replace('\\', '/');

            if (normalized.contains("src/main/resources/db/migration/")) {
                String owner = owningPersistenceModule(relative);
                assertNotNull(owner, file + " 的 Flyway 迁移不属于任何领域服务或官方 Example");
                String fileName = file.getFileName().toString();
                Matcher versioned = VERSIONED_MIGRATION.matcher(fileName);
                Matcher repeatable = REPEATABLE_MIGRATION.matcher(fileName);
                assertTrue(versioned.matches() || repeatable.matches(), file + " 不符合 Flyway 文件命名规范");
                if (versioned.matches()) {
                    assertTrue(migrationVersions.computeIfAbsent(owner, ignored -> new HashSet<>())
                                    .add(versioned.group(1)),
                            owner + " 存在重复 Flyway 版本 " + versioned.group(1));
                } else {
                    String sql = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(REPEATABLE_FORBIDDEN_SQL.matcher(sql).find(),
                            file + " 的 Repeatable Migration 不得修改表结构或业务数据");
                }
            }

            if (file.getFileName().toString().endsWith("Mapper.xml")) {
                String owner = owningPersistenceModule(relative);
                assertNotNull(owner, file + " 的 Mapper XML 不属于任何领域服务或官方 Example");
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                Matcher namespace = MAPPER_NAMESPACE.matcher(xml);
                assertTrue(namespace.find(), file + " 必须声明 Mapper namespace");
                if (SERVICE_PACKAGES.containsKey(owner)) {
                    assertTrue(namespace.group(1).startsWith(SERVICE_PACKAGES.get(owner) + "."),
                            file + " 的 namespace 必须属于服务包 " + SERVICE_PACKAGES.get(owner));
                }
            }

            if (isPublicPersistenceType(relative)) {
                throw new AssertionError(file + " 在公共或接入模块中暴露了持久化类型");
            }
        }
    }

    @Test
    void mapperInterfacesAndXmlStatementsStayAligned() throws Exception {
        Map<String, Path> xmlByNamespace = new HashMap<>();
        Map<String, Set<String>> statementsByNamespace = new HashMap<>();

        for (Path xmlFile : filesUnder(REPOSITORY, "Mapper.xml")) {
            Path relative = REPOSITORY.relativize(xmlFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
            Matcher namespaceMatcher = MAPPER_NAMESPACE.matcher(xml);
            assertTrue(namespaceMatcher.find(), xmlFile + " 必须声明 Mapper namespace");
            String namespace = namespaceMatcher.group(1);
            assertTrue(xmlByNamespace.put(namespace, xmlFile) == null, "Mapper namespace 重复: " + namespace);

            Set<String> statementIds = new LinkedHashSet<>();
            Matcher statementMatcher = MAPPER_STATEMENT.matcher(xml);
            while (statementMatcher.find()) {
                assertTrue(statementIds.add(statementMatcher.group(1)),
                        xmlFile + " 存在重复 statement id " + statementMatcher.group(1));
            }
            statementsByNamespace.put(namespace, statementIds);
        }

        for (Path javaFile : filesUnder(REPOSITORY, "Mapper.java")) {
            Path relative = REPOSITORY.relativize(javaFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String packageName = matchRequired(source, Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+);"), javaFile,
                    "Mapper 接口必须声明 package");
            String namespace = packageName + "." + javaFile.getFileName().toString().replace(".java", "");
            assertTrue(xmlByNamespace.containsKey(namespace), javaFile + " 缺少 namespace 为 " + namespace + " 的 Mapper XML");

            Set<String> methodNames = new LinkedHashSet<>();
            Matcher methodMatcher = MAPPER_METHOD.matcher(source);
            while (methodMatcher.find()) {
                assertTrue(methodNames.add(methodMatcher.group(1)),
                        javaFile + " 不得声明同名重载 Mapper 方法 " + methodMatcher.group(1));
            }
            assertEquals(methodNames, statementsByNamespace.get(namespace),
                    javaFile + " 的方法必须与 Mapper XML statement id 一一对应");
        }

        Set<String> javaNamespaces = new HashSet<>();
        for (Path javaFile : filesUnder(REPOSITORY, "Mapper.java")) {
            Path relative = REPOSITORY.relativize(javaFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String packageName = matchRequired(source, Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+);"), javaFile,
                    "Mapper 接口必须声明 package");
            javaNamespaces.add(packageName + "." + javaFile.getFileName().toString().replace(".java", ""));
        }
        assertEquals(javaNamespaces, xmlByNamespace.keySet(), "每个 Mapper XML 必须存在唯一对应的 Mapper 接口");
    }

    @Test
    void servicesDoNotDependOnOtherServiceImplementations() throws Exception {
        List<Path> pomFiles = filesUnder(REPOSITORY, "pom.xml");
        for (Path pomFile : pomFiles) {
            Document pom = parseXml(pomFile);
            NodeList dependencies = pom.getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                org.w3c.dom.Node dependency = dependencies.item(index);
                String groupId = childText(dependency, "groupId");
                String artifactId = childText(dependency, "artifactId");
                if ("io.saasforge".equals(groupId) && SERVICE_ARTIFACTS.contains(artifactId)) {
                    Path relativePom = REPOSITORY.relativize(pomFile);
                    assertTrue(relativePom.startsWith(Path.of("services", artifactId)),
                            pomFile + " 不得依赖领域服务实现 " + artifactId);
                }
            }
        }
    }

    @Test
    void governanceDocumentsAndContractsAreVersionedTogether() {
        for (String relative : List.of(
                "docs/11-database-design.md",
                "docs/19-redis-key-registry.md",
                "docs/20-application-logging.md",
                "docs/adr/0011-versioned-data-cache-and-logging-standards.md",
                "contracts/redis/registry.schema.json",
                "contracts/logging/application-log.schema.json",
                "contracts/logging/policy.json")) {
            assertTrue(Files.isRegularFile(REPOSITORY.resolve(relative)), "缺少治理文件 " + relative);
        }
    }

    private static JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    private static String requiredText(JsonNode node, String field, Path source) {
        assertTrue(node.has(field) && node.path(field).isTextual() && !node.path(field).asText().isBlank(),
                source + " 缺少非空文本字段 " + field);
        return node.path(field).asText();
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        assertTrue(array.isArray(), "预期 JSON 数组，实际为 " + array);
        array.forEach(value -> {
            assertTrue(value.isTextual(), "数组值必须是字符串: " + value);
            assertTrue(values.add(value.asText()), "数组值重复: " + value.asText());
        });
        return values;
    }

    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }

    private static List<Path> repositoryFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(REPOSITORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(REPOSITORY.resolve(".git")))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }

    private static String owningPersistenceModule(Path relative) {
        if (relative.getNameCount() < 2 || !relative.getName(0).toString().equals("services")) {
            if (relative.getNameCount() >= 2 && relative.getName(0).toString().equals("examples")) {
                return "examples/" + relative.getName(1);
            }
            return null;
        }
        String service = relative.getName(1).toString();
        return SERVICE_ARTIFACTS.contains(service) ? service : null;
    }

    private static boolean isPublicPersistenceType(Path relative) {
        if (relative.getNameCount() < 2 || owningPersistenceModule(relative) != null) {
            return false;
        }
        String normalized = relative.toString().replace('\\', '/');
        if (!normalized.contains("/src/main/") && !normalized.startsWith("src/main/")) {
            return false;
        }
        String fileName = relative.getFileName().toString();
        return fileName.endsWith("Mapper.java")
                || fileName.endsWith("Mapper.xml")
                || fileName.endsWith("DO.java")
                || fileName.endsWith("PO.java")
                || fileName.endsWith("PersistenceEntity.java")
                || fileName.endsWith("DatabaseEntity.java");
    }

    private static String matchRequired(String source, Pattern pattern, Path path, String message) {
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), path + " " + message);
        return matcher.group(1);
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static String childText(org.w3c.dom.Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            org.w3c.dom.Node child = children.item(index);
            if (child.getNodeName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }
}
