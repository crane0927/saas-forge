package io.saasforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1ContractCompatibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of(System.getProperty("repositoryRoot"));
    private static final Path BASELINES = REPOSITORY.resolve("contracts/compatibility-baselines/v1");
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final List<String> EQUAL_SCHEMA_KEYWORDS = List.of(
            "type", "format", "pattern", "const", "multipleOf", "uniqueItems");
    private static final List<String> LOWER_BOUND_KEYWORDS = List.of(
            "minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties");
    private static final List<String> UPPER_BOUND_KEYWORDS = List.of(
            "maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    private static final Pattern FIELD = Pattern.compile(
            "(?m)^\\s*(?:(repeated|optional)\\s+)?([A-Za-z][A-Za-z0-9_.]*(?:<[A-Za-z][A-Za-z0-9_., ]*>)?)\\s+"
                    + "([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*(\\d+)\\s*(?:\\[[^]]*])?\\s*;");
    private static final Pattern RPC = Pattern.compile(
            "(?m)^\\s*rpc\\s+([A-Za-z][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z0-9_.]+)\\s*\\)\\s*"
                    + "returns\\s*\\(\\s*([A-Za-z0-9_.]+)\\s*\\)\\s*;");

    @Test
    void currentV1ContractsRemainCompatibleWithEveryPublishedBaseline() throws Exception {
        assertTrue(Files.isDirectory(BASELINES), "缺少已发布 v1 契约基线目录: " + BASELINES);
        List<Path> baselines;
        try (Stream<Path> paths = Files.list(BASELINES)) {
            baselines = paths.filter(Files::isDirectory).sorted().toList();
        }
        assertFalse(baselines.isEmpty(), "至少需要一份已发布 v1 契约基线");

        for (Path baseline : baselines) {
            assertOpenApiCompatible(baseline);
            assertProtobufCompatible(baseline);
            assertEventSchemasCompatible(baseline);
        }
    }

    private static void assertOpenApiCompatible(Path baseline) throws IOException {
        Map<String, Object> baselineApi = readYaml(baseline.resolve("openapi/v1.yaml"));
        Map<String, Object> baselineCommon = readYaml(baseline.resolve("openapi/common.yaml"));
        Map<String, Object> currentApi = readYaml(REPOSITORY.resolve("contracts/openapi/v1.yaml"));
        Map<String, Object> currentCommon = readYaml(REPOSITORY.resolve("contracts/openapi/common.yaml"));
        Map<String, Map<String, Object>> baselineDocuments = Map.of(
                "v1.yaml", openApiReferenceDocument(baselineApi, baselineCommon), "common.yaml", baselineCommon);
        Map<String, Map<String, Object>> currentDocuments = Map.of(
                "v1.yaml", openApiReferenceDocument(currentApi, currentCommon), "common.yaml", currentCommon);

        Map<String, Object> baselinePaths = map(baselineApi.get("paths"), baseline + " OpenAPI paths");
        Map<String, Object> currentPaths = map(currentApi.get("paths"), "当前 OpenAPI paths");
        for (Map.Entry<String, Object> path : baselinePaths.entrySet()) {
            Map<String, Object> currentPath = map(currentPaths.get(path.getKey()), "当前 OpenAPI path " + path.getKey());
            for (Map.Entry<String, Object> operation : map(path.getValue(), baseline + " path " + path.getKey()).entrySet()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                Map<String, Object> currentOperation = map(currentPath.get(operation.getKey()),
                        "当前 OpenAPI operation " + operation.getKey().toUpperCase() + " " + path.getKey());
                assertOperationCompatible(map(operation.getValue(), baseline + " operation " + path.getKey()), currentOperation,
                        baselineDocuments, currentDocuments, operation.getKey().toUpperCase() + " " + path.getKey());
            }
        }
    }

    private static void assertOperationCompatible(
            Map<String, Object> baseline,
            Map<String, Object> current,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        assertEquals(baseline.get("operationId"), current.get("operationId"), location + " 的 operationId 不可改变");
        assertParametersCompatible(baseline.get("parameters"), current.get("parameters"), baselineDocuments, currentDocuments, location);
        assertRequestBodyCompatible(baseline.get("requestBody"), current.get("requestBody"),
                baselineDocuments, currentDocuments, location);
        assertResponsesCompatible(baseline.get("responses"), current.get("responses"),
                baselineDocuments, currentDocuments, location);
    }

    private static void assertParametersCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        Map<String, Map<String, Object>> currentByIdentity = new HashMap<>();
        for (Object parameter : list(currentNode)) {
            Map<String, Object> resolved = resolveOpenApiMap(parameter, currentDocuments, "v1.yaml");
            currentByIdentity.put(parameterIdentity(resolved), resolved);
        }
        for (Object parameter : list(baselineNode)) {
            Map<String, Object> baseline = resolveOpenApiMap(parameter, baselineDocuments, "v1.yaml");
            String identity = parameterIdentity(baseline);
            Map<String, Object> current = currentByIdentity.get(identity);
            assertNotNull(current, location + " 删除了参数 " + identity);
            assertFalse(!required(baseline) && required(current),
                    location + " 将可选参数改为必填: " + identity);
            assertSchemaCompatible(baseline.get("schema"), current.get("schema"),
                    baselineDocuments, currentDocuments, location + " parameter " + identity);
        }
    }

    private static void assertRequestBodyCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        if (baselineNode == null) {
            return;
        }
        Map<String, Object> baseline = resolveOpenApiMap(baselineNode, baselineDocuments, "v1.yaml");
        Map<String, Object> current = resolveOpenApiMap(currentNode, currentDocuments, "v1.yaml");
        assertFalse(!required(baseline) && required(current),
                location + " 将可选 requestBody 改为必填");
        assertContentCompatible(baseline.get("content"), current.get("content"),
                baselineDocuments, currentDocuments, location + " requestBody");
    }

    private static void assertResponsesCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        Map<String, Object> baseline = map(baselineNode, location + " responses");
        Map<String, Object> current = map(currentNode, location + " responses");
        for (Map.Entry<String, Object> response : baseline.entrySet()) {
            Map<String, Object> baselineResponse = resolveOpenApiMap(response.getValue(), baselineDocuments, "v1.yaml");
            Map<String, Object> currentResponse = resolveOpenApiMap(current.get(response.getKey()), currentDocuments, "v1.yaml");
            assertContentCompatible(baselineResponse.get("content"), currentResponse.get("content"),
                    baselineDocuments, currentDocuments, location + " response " + response.getKey());
            assertHeadersCompatible(baselineResponse.get("headers"), currentResponse.get("headers"),
                    baselineDocuments, currentDocuments, location + " response " + response.getKey());
        }
    }

    private static void assertHeadersCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        if (baselineNode == null) {
            return;
        }
        Map<String, Object> baseline = map(baselineNode, location + " headers");
        Map<String, Object> current = map(currentNode, location + " headers");
        for (Map.Entry<String, Object> header : baseline.entrySet()) {
            Map<String, Object> baselineHeader = resolveOpenApiMap(header.getValue(), baselineDocuments, "v1.yaml");
            Map<String, Object> currentHeader = resolveOpenApiMap(current.get(header.getKey()), currentDocuments, "v1.yaml");
            assertFalse(!required(baselineHeader) && required(currentHeader),
                    location + " 将可选响应头改为必填: " + header.getKey());
            assertSchemaCompatible(baselineHeader.get("schema"), currentHeader.get("schema"),
                    baselineDocuments, currentDocuments, location + " header " + header.getKey());
        }
    }

    private static void assertContentCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        if (baselineNode == null) {
            return;
        }
        Map<String, Object> baseline = map(baselineNode, location + " content");
        Map<String, Object> current = map(currentNode, location + " content");
        for (Map.Entry<String, Object> mediaType : baseline.entrySet()) {
            Map<String, Object> baselineMedia = map(mediaType.getValue(), location + " media type " + mediaType.getKey());
            Map<String, Object> currentMedia = map(current.get(mediaType.getKey()), location + " media type " + mediaType.getKey());
            assertSchemaCompatible(baselineMedia.get("schema"), currentMedia.get("schema"),
                    baselineDocuments, currentDocuments, location + " media type " + mediaType.getKey());
        }
    }

    private static void assertProtobufCompatible(Path baseline) throws IOException {
        Path baselineRoot = baseline.resolve("protobuf");
        for (Path baselineProto : filesUnder(baselineRoot, ".proto")) {
            Path relative = baselineRoot.relativize(baselineProto);
            Path currentProto = REPOSITORY.resolve("contracts/protobuf").resolve(relative);
            String location = "Protobuf " + relative;
            String baselineSource = Files.readString(baselineProto, StandardCharsets.UTF_8);
            String currentSource = Files.readString(currentProto, StandardCharsets.UTF_8);
            assertEquals(packageName(baselineSource), packageName(currentSource), location + " 的 package 不可改变");

            Map<String, String> baselineServices = namedBlocks(baselineSource, "service");
            Map<String, String> currentServices = namedBlocks(currentSource, "service");
            for (Map.Entry<String, String> service : baselineServices.entrySet()) {
                String currentService = currentServices.get(service.getKey());
                assertNotNull(currentService, location + " 删除了 service " + service.getKey());
                Map<String, Rpc> currentRpcs = rpcs(currentService);
                for (Map.Entry<String, Rpc> rpc : rpcs(service.getValue()).entrySet()) {
                    assertEquals(rpc.getValue(), currentRpcs.get(rpc.getKey()),
                            location + " 改变或删除了 RPC " + service.getKey() + "." + rpc.getKey());
                }
            }

            Map<String, String> baselineMessages = namedBlocks(baselineSource, "message");
            Map<String, String> currentMessages = namedBlocks(currentSource, "message");
            for (Map.Entry<String, String> message : baselineMessages.entrySet()) {
                String currentMessage = currentMessages.get(message.getKey());
                assertNotNull(currentMessage, location + " 删除了 message " + message.getKey());
                Map<Integer, ProtoField> currentFields = fields(currentMessage);
                for (Map.Entry<Integer, ProtoField> field : fields(message.getValue()).entrySet()) {
                    assertEquals(field.getValue(), currentFields.get(field.getKey()),
                            location + " 改变或删除了 " + message.getKey() + " 的 field tag " + field.getKey());
                }
            }
        }
    }

    private static void assertEventSchemasCompatible(Path baseline) throws IOException {
        Path baselineRoot = baseline.resolve("events");
        for (Path baselineSchema : filesUnder(baselineRoot, ".schema.json")) {
            Path relative = baselineRoot.relativize(baselineSchema);
            Path currentSchema = REPOSITORY.resolve("contracts/events").resolve(relative);
            assertJsonSchemaCompatible(readJson(baselineSchema), readJson(currentSchema),
                    "事件 schema " + relative);
        }
    }

    private static void assertSchemaCompatible(
            Object baselineNode,
            Object currentNode,
            Map<String, Map<String, Object>> baselineDocuments,
            Map<String, Map<String, Object>> currentDocuments,
            String location) {
        if (baselineNode == null) {
            return;
        }
        Map<String, Object> baseline = resolveOpenApiMap(baselineNode, baselineDocuments, "v1.yaml");
        Map<String, Object> current = resolveOpenApiMap(currentNode, currentDocuments, "v1.yaml");
        assertMapSchemaCompatible(baseline, current, location,
                (oldNode, newNode, nestedLocation) -> assertSchemaCompatible(oldNode, newNode,
                        baselineDocuments, currentDocuments, nestedLocation));
    }

    private static void assertJsonSchemaCompatible(JsonNode baseline, JsonNode current, String location) {
        assertTrue(current.isObject(), location + " 不再是 object schema");
        Map<String, Object> baselineMap = jsonMap(baseline);
        Map<String, Object> currentMap = jsonMap(current);
        assertMapSchemaCompatible(baselineMap, currentMap, location,
                (oldNode, newNode, nestedLocation) -> assertJsonSchemaCompatible(JSON.valueToTree(oldNode),
                        JSON.valueToTree(newNode), nestedLocation));
    }

    private static void assertMapSchemaCompatible(
            Map<String, Object> baseline,
            Map<String, Object> current,
            String location,
            SchemaComparator comparator) {
        for (String keyword : EQUAL_SCHEMA_KEYWORDS) {
            if (baseline.containsKey(keyword)) {
                assertEquals(baseline.get(keyword), current.get(keyword), location + " 改变了 " + keyword);
            }
        }
        if (baseline.containsKey("enum")) {
            assertTrue(list(current.get("enum")).containsAll(list(baseline.get("enum"))),
                    location + " 收紧或删除了 enum 值");
        }
        assertEquals(new LinkedHashSet<>(list(baseline.get("required"))), new LinkedHashSet<>(list(current.get("required"))),
                location + " 改变了 required 字段");
        for (String keyword : LOWER_BOUND_KEYWORDS) {
            assertBoundNotTightened(baseline, current, keyword, true, location);
        }
        for (String keyword : UPPER_BOUND_KEYWORDS) {
            assertBoundNotTightened(baseline, current, keyword, false, location);
        }

        Map<String, Object> baselineProperties = mapOrEmpty(baseline.get("properties"), location + " properties");
        Map<String, Object> currentProperties = mapOrEmpty(current.get("properties"), location + " properties");
        for (Map.Entry<String, Object> property : baselineProperties.entrySet()) {
            assertTrue(currentProperties.containsKey(property.getKey()), location + " 删除了字段 " + property.getKey());
            comparator.compare(property.getValue(), currentProperties.get(property.getKey()),
                    location + " property " + property.getKey());
        }
        compareOptionalSchemaValue(baseline, current, "items", location, comparator);
        compareOptionalSchemaValue(baseline, current, "additionalProperties", location, comparator);
        for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
            if (!baseline.containsKey(keyword)) {
                continue;
            }
            List<Object> baselineBranches = list(baseline.get(keyword));
            List<Object> currentBranches = list(current.get(keyword));
            if ("allOf".equals(keyword)) {
                assertEquals(baselineBranches.size(), currentBranches.size(), location + " 改变了 " + keyword);
            } else {
                assertTrue(currentBranches.size() >= baselineBranches.size(), location + " 删除了 " + keyword + " 分支");
            }
            for (int index = 0; index < baselineBranches.size(); index++) {
                comparator.compare(baselineBranches.get(index), currentBranches.get(index),
                        location + " " + keyword + "[" + index + "]");
            }
        }
    }

    private static void compareOptionalSchemaValue(
            Map<String, Object> baseline,
            Map<String, Object> current,
            String keyword,
            String location,
            SchemaComparator comparator) {
        if (!baseline.containsKey(keyword)) {
            return;
        }
        Object baselineValue = baseline.get(keyword);
        Object currentValue = current.get(keyword);
        assertNotNull(currentValue, location + " 删除了 " + keyword);
        if (baselineValue instanceof Boolean || currentValue instanceof Boolean) {
            assertFalse(Boolean.TRUE.equals(baselineValue) && Boolean.FALSE.equals(currentValue),
                    location + " 收紧了 " + keyword);
            return;
        }
        comparator.compare(baselineValue, currentValue, location + " " + keyword);
    }

    private static void assertBoundNotTightened(
            Map<String, Object> baseline,
            Map<String, Object> current,
            String keyword,
            boolean lowerBound,
            String location) {
        if (!baseline.containsKey(keyword)) {
            assertFalse(current.containsKey(keyword), location + " 新增了 " + keyword + " 约束");
            return;
        }
        if (!current.containsKey(keyword)) {
            return;
        }
        double baselineValue = number(baseline.get(keyword), location + " " + keyword);
        double currentValue = number(current.get(keyword), location + " " + keyword);
        assertTrue(lowerBound ? currentValue <= baselineValue : currentValue >= baselineValue,
                location + " 收紧了 " + keyword + " 约束");
    }

    private static Map<String, Object> resolveOpenApiMap(
            Object node,
            Map<String, Map<String, Object>> documents,
            String defaultDocument) {
        Object resolved = node;
        String documentName = defaultDocument;
        while (resolved instanceof Map<?, ?> raw && raw.containsKey("$ref")) {
            String reference = String.valueOf(raw.get("$ref"));
            String[] parts = reference.split("#", 2);
            if (!parts[0].isBlank()) {
                documentName = Path.of(parts[0]).getFileName().toString();
            }
            Object target = documents.get(documentName);
            assertNotNull(target, "无法解析 OpenAPI 引用: " + reference);
            if (parts.length == 2 && !parts[1].isBlank()) {
                for (String segment : parts[1].substring(1).split("/")) {
                    target = map(target, "OpenAPI 引用 " + reference).get(segment.replace("~1", "/").replace("~0", "~"));
                }
            }
            resolved = target;
        }
        return map(resolved, "OpenAPI 契约节点: " + node);
    }

    private static String parameterIdentity(Map<String, Object> parameter) {
        return String.valueOf(parameter.get("in")) + ":" + String.valueOf(parameter.get("name"));
    }

    // common.yaml 中的本地引用在 v1.yaml 引用链中继续解析，因此构造仅供解析的组合组件视图。
    private static Map<String, Object> openApiReferenceDocument(Map<String, Object> api, Map<String, Object> common) {
        Map<String, Object> document = new LinkedHashMap<>(api);
        Map<String, Object> components = new LinkedHashMap<>(mapOrEmpty(common.get("components"), "common OpenAPI components"));
        for (Map.Entry<String, Object> entry : mapOrEmpty(api.get("components"), "v1 OpenAPI components").entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> && components.get(entry.getKey()) instanceof Map<?, ?>) {
                Map<String, Object> category = new LinkedHashMap<>(map(components.get(entry.getKey()),
                        "common OpenAPI component category " + entry.getKey()));
                category.putAll(map(entry.getValue(), "v1 OpenAPI component category " + entry.getKey()));
                components.put(entry.getKey(), category);
            } else {
                components.put(entry.getKey(), entry.getValue());
            }
        }
        document.put("components", components);
        return document;
    }

    private static Map<String, String> namedBlocks(String source, String keyword) {
        Pattern start = Pattern.compile("\\b" + keyword + "\\s+([A-Za-z][A-Za-z0-9_]*)\\s*\\{");
        Matcher matcher = start.matcher(source);
        Map<String, String> blocks = new LinkedHashMap<>();
        while (matcher.find()) {
            int depth = 1;
            int index = matcher.end();
            for (; index < source.length() && depth > 0; index++) {
                char character = source.charAt(index);
                if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                }
            }
            assertEquals(0, depth, "Protobuf " + keyword + " 缺少闭合大括号: " + matcher.group(1));
            blocks.put(matcher.group(1), source.substring(matcher.end(), index - 1));
        }
        return blocks;
    }

    private static Map<Integer, ProtoField> fields(String message) {
        Map<Integer, ProtoField> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(message);
        while (matcher.find()) {
            int tag = Integer.parseInt(matcher.group(4));
            assertTrue(fields.put(tag, new ProtoField(matcher.group(2), matcher.group(3),
                    matcher.group(1) == null ? "" : matcher.group(1), "")) == null,
                    "Protobuf field tag 重复: " + tag);
        }
        for (Map.Entry<String, String> oneof : namedBlocks(message, "oneof").entrySet()) {
            Matcher oneofFields = FIELD.matcher(oneof.getValue());
            while (oneofFields.find()) {
                int tag = Integer.parseInt(oneofFields.group(4));
                fields.put(tag, new ProtoField(oneofFields.group(2), oneofFields.group(3),
                        oneofFields.group(1) == null ? "" : oneofFields.group(1), oneof.getKey()));
            }
        }
        return fields;
    }

    private static Map<String, Rpc> rpcs(String service) {
        Map<String, Rpc> rpcs = new LinkedHashMap<>();
        Matcher matcher = RPC.matcher(service);
        while (matcher.find()) {
            assertTrue(rpcs.put(matcher.group(1), new Rpc(matcher.group(2), matcher.group(3))) == null,
                    "Protobuf RPC 重复: " + matcher.group(1));
        }
        return rpcs;
    }

    private static String packageName(String source) {
        Matcher matcher = PACKAGE.matcher(source);
        assertTrue(matcher.find(), "Protobuf 缺少 package 声明");
        return matcher.group(1);
    }

    private static JsonNode readJson(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "缺少事件 schema: " + path);
        return JSON.readTree(path.toFile());
    }

    private static Map<String, Object> jsonMap(JsonNode node) {
        Map<String, Object> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class)));
        return values;
    }

    private static Map<String, Object> readYaml(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "缺少 OpenAPI 契约: " + path);
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return map(loaded, path + " YAML 根节点");
        }
    }

    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        assertTrue(Files.isDirectory(root), "缺少契约基线目录: " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, Object> map(Object value, String location) {
        assertNotNull(value, location + " 缺失");
        assertTrue(value instanceof Map<?, ?>, location + " 必须是 object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> mapOrEmpty(Object value, String location) {
        return value == null ? Map.of() : map(value, location);
    }

    private static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        assertTrue(value instanceof List<?>, "契约数组字段必须是 array");
        return new ArrayList<>((List<?>) value);
    }

    private static double number(Object value, String location) {
        assertTrue(value instanceof Number, location + " 必须是数字");
        return ((Number) value).doubleValue();
    }

    private static boolean required(Map<String, Object> value) {
        return Boolean.TRUE.equals(value.get("required"));
    }

    @FunctionalInterface
    private interface SchemaComparator {
        void compare(Object baseline, Object current, String location);
    }

    private record ProtoField(String type, String name, String label, String oneof) {
    }

    private record Rpc(String requestType, String responseType) {
    }
}
