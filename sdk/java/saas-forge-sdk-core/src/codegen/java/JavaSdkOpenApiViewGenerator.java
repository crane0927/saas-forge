import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从唯一正式 OpenAPI 中派生仅供 Java SDK 代码生成使用的临时视图。 */
public final class JavaSdkOpenApiViewGenerator {
    private static final String PUBLICATION_MARKER = "      x-saasforge-java-sdk: true";
    private static final Pattern PATH = Pattern.compile("^  /[^:]+:$");
    private static final Pattern METHOD = Pattern.compile("^    (get|post|put|patch|delete|head|options|trace):$");
    private static final Pattern COMPONENT_SECTION = Pattern.compile("^  ([A-Za-z][A-Za-z0-9]*):$");
    private static final Pattern COMPONENT_ENTRY = Pattern.compile("^    ([A-Za-z][A-Za-z0-9_.-]*):(?:\\s.*)?$");
    private static final Pattern REFERENCE = Pattern.compile("\\$ref:\\s*['\"]?([^'\"}\\s]+)");

    private JavaSdkOpenApiViewGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("需要正式 OpenAPI 目录和派生视图输出目录");
        }
        Path sourceDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        SourceDocument root = SourceDocument.read("v1.yaml", sourceDirectory.resolve("v1.yaml"));
        SourceDocument common = SourceDocument.read("common.yaml", sourceDirectory.resolve("common.yaml"));

        List<String> publishedPaths = publishedPaths(root);
        require(!publishedPaths.isEmpty(), "正式 OpenAPI 没有已批准的 Java SDK operation");

        Map<ComponentKey, List<String>> components = new HashMap<>();
        components.putAll(root.components());
        components.putAll(common.components());
        Set<ComponentKey> requiredComponents = requiredComponents(root, publishedPaths, components);

        Files.createDirectories(outputDirectory);
        Files.write(outputDirectory.resolve("java-sdk-v1.yaml"),
                renderRoot(root, publishedPaths, requiredComponents), StandardCharsets.UTF_8);
        Files.write(outputDirectory.resolve("common.yaml"),
                renderComponentsDocument(common, requiredComponents), StandardCharsets.UTF_8);
    }

    private static List<String> publishedPaths(SourceDocument root) {
        List<String> output = new ArrayList<>();
        List<String> lines = root.lines();
        output.add("paths:");
        for (int start = root.pathsIndex() + 1; start < root.componentsIndex();) {
            if (!PATH.matcher(lines.get(start)).matches()) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < root.componentsIndex() && !PATH.matcher(lines.get(end)).matches()) {
                end++;
            }
            List<String> filtered = publishedOperations(lines.subList(start, end));
            if (!filtered.isEmpty()) {
                output.addAll(filtered);
            }
            start = end;
        }
        return output;
    }

    private static List<String> publishedOperations(List<String> pathBlock) {
        List<String> output = new ArrayList<>();
        List<String> pathLevel = new ArrayList<>();
        boolean published = false;
        int cursor = 1;
        while (cursor < pathBlock.size() && !METHOD.matcher(pathBlock.get(cursor)).matches()) {
            pathLevel.add(pathBlock.get(cursor++));
        }
        while (cursor < pathBlock.size()) {
            int end = cursor + 1;
            while (end < pathBlock.size() && !METHOD.matcher(pathBlock.get(end)).matches()) {
                end++;
            }
            List<String> operation = pathBlock.subList(cursor, end);
            if (operation.contains(PUBLICATION_MARKER)) {
                if (!published) {
                    output.add(pathBlock.get(0));
                    output.addAll(pathLevel);
                    published = true;
                }
                output.addAll(operation);
            }
            cursor = end;
        }
        return output;
    }

    private static Set<ComponentKey> requiredComponents(
            SourceDocument root,
            List<String> publishedPaths,
            Map<ComponentKey, List<String>> components) {
        Set<ComponentKey> required = new LinkedHashSet<>();
        ArrayDeque<ComponentKey> pending = new ArrayDeque<>();
        collectReferences("v1.yaml", publishedPaths, pending);

        Set<String> securitySchemes = root.componentNames("securitySchemes");
        for (String scheme : referencedSecuritySchemes(publishedPaths, securitySchemes)) {
            pending.add(new ComponentKey("v1.yaml", "securitySchemes", scheme));
        }

        while (!pending.isEmpty()) {
            ComponentKey key = pending.removeFirst();
            if (!required.add(key)) {
                continue;
            }
            List<String> component = components.get(key);
            require(component != null, "派生视图引用了不存在的组件: " + key);
            collectReferences(key.document(), component, pending);
        }
        return required;
    }

    private static Set<String> referencedSecuritySchemes(List<String> lines, Set<String> knownSchemes) {
        Set<String> referenced = new LinkedHashSet<>();
        boolean readingSecurity = false;
        for (String line : lines) {
            int indentation = indentation(line);
            if (line.startsWith("      security:")) {
                readingSecurity = true;
            } else if (readingSecurity && indentation <= 6 && !line.isBlank()) {
                readingSecurity = false;
            }
            if (readingSecurity) {
                for (String scheme : knownSchemes) {
                    if (line.contains(scheme + ":")) {
                        referenced.add(scheme);
                    }
                }
            }
        }
        return referenced;
    }

    private static void collectReferences(
            String currentDocument, List<String> lines, ArrayDeque<ComponentKey> pending) {
        for (String line : lines) {
            Matcher matcher = REFERENCE.matcher(line);
            while (matcher.find()) {
                pending.add(referenceKey(currentDocument, matcher.group(1)));
            }
        }
    }

    private static ComponentKey referenceKey(String currentDocument, String reference) {
        int fragment = reference.indexOf("#/components/");
        require(fragment >= 0, "Java SDK 派生视图只支持 OpenAPI components 引用: " + reference);
        String document = fragment == 0
                ? currentDocument
                : Path.of(reference.substring(0, fragment)).getFileName().toString();
        String[] parts = reference.substring(fragment + 2).split("/");
        require(parts.length == 3 && "components".equals(parts[0]), "OpenAPI components 引用非法: " + reference);
        return new ComponentKey(document, parts[1], parts[2]);
    }

    private static List<String> renderRoot(
            SourceDocument root, List<String> publishedPaths, Set<ComponentKey> required) {
        List<String> output = new ArrayList<>(root.lines().subList(0, root.pathsIndex()));
        output.addAll(publishedPaths);
        output.addAll(renderComponents(root, required));
        return output;
    }

    private static List<String> renderComponentsDocument(
            SourceDocument document, Set<ComponentKey> required) {
        List<String> output = new ArrayList<>(document.lines().subList(0, document.componentsIndex()));
        output.addAll(renderComponents(document, required));
        return output;
    }

    private static List<String> renderComponents(SourceDocument document, Set<ComponentKey> required) {
        List<String> output = new ArrayList<>();
        output.add("components:");
        for (Map.Entry<String, List<ComponentBlock>> section : document.sections().entrySet()) {
            List<ComponentBlock> selected = section.getValue().stream()
                    .filter(block -> required.contains(new ComponentKey(
                            document.name(), section.getKey(), block.name())))
                    .toList();
            if (selected.isEmpty()) {
                continue;
            }
            output.add("  " + section.getKey() + ":");
            selected.forEach(block -> output.addAll(block.lines()));
        }
        return output;
    }

    private static int indentation(String line) {
        int indentation = 0;
        while (indentation < line.length() && line.charAt(indentation) == ' ') {
            indentation++;
        }
        return indentation;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record ComponentKey(String document, String section, String name) {
    }

    private record ComponentBlock(String name, List<String> lines) {
    }

    private record SourceDocument(
            String name,
            List<String> lines,
            int pathsIndex,
            int componentsIndex,
            Map<String, List<ComponentBlock>> sections,
            Map<ComponentKey, List<String>> components) {

        private static SourceDocument read(String name, Path path) throws IOException {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int pathsIndex = lines.indexOf("paths:");
            if (pathsIndex < 0) {
                pathsIndex = lines.indexOf("paths: {}");
            }
            int componentsIndex = lines.indexOf("components:");
            require(pathsIndex >= 0 && componentsIndex > pathsIndex,
                    path + " 必须包含 paths 和 components");

            Map<String, List<ComponentBlock>> sections = new LinkedHashMap<>();
            Map<ComponentKey, List<String>> components = new HashMap<>();
            String section = null;
            for (int cursor = componentsIndex + 1; cursor < lines.size();) {
                Matcher sectionMatcher = COMPONENT_SECTION.matcher(lines.get(cursor));
                if (sectionMatcher.matches()) {
                    section = sectionMatcher.group(1);
                    sections.putIfAbsent(section, new ArrayList<>());
                    cursor++;
                    continue;
                }
                Matcher entryMatcher = COMPONENT_ENTRY.matcher(lines.get(cursor));
                if (section == null || !entryMatcher.matches()) {
                    cursor++;
                    continue;
                }
                String entry = entryMatcher.group(1);
                int end = cursor + 1;
                while (end < lines.size()
                        && !COMPONENT_SECTION.matcher(lines.get(end)).matches()
                        && !COMPONENT_ENTRY.matcher(lines.get(end)).matches()) {
                    end++;
                }
                List<String> blockLines = List.copyOf(lines.subList(cursor, end));
                ComponentBlock block = new ComponentBlock(entry, blockLines);
                sections.get(section).add(block);
                components.put(new ComponentKey(name, section, entry), blockLines);
                cursor = end;
            }
            return new SourceDocument(name, lines, pathsIndex, componentsIndex, sections, components);
        }

        private Set<String> componentNames(String section) {
            Set<String> names = new HashSet<>();
            for (ComponentBlock block : sections.getOrDefault(section, List.of())) {
                names.add(block.name());
            }
            return names;
        }
    }
}
