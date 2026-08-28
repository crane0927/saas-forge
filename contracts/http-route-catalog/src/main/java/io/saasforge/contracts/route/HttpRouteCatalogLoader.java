package io.saasforge.contracts.route;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 严格加载当前进程随制品发布的 Route Catalog；运行时配置和服务发现不能替换该资源。
 */
public final class HttpRouteCatalogLoader {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String RESOURCE = "META-INF/saasforge/http-route-catalog.json";
    private static final Pattern OPERATION_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9]*$");
    private static final Pattern SERVICE_ID = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern SCOPE = Pattern.compile("^[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)+$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]+}");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);

    private HttpRouteCatalogLoader() {
    }

    public static HttpRouteCatalog load() {
        ClassLoader classLoader = HttpRouteCatalogLoader.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少 HTTP Route Catalog 资源 " + RESOURCE);
            }
            return load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 HTTP Route Catalog", exception);
        }
    }

    static HttpRouteCatalog load(InputStream input) {
        try {
            HttpRouteCatalog catalog = JSON.readValue(input, HttpRouteCatalog.class);
            validate(catalog);
            return catalog;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("HTTP Route Catalog 非法", exception);
        }
    }

    private static void validate(HttpRouteCatalog catalog) {
        if (catalog == null || catalog.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的 HTTP Route Catalog schemaVersion");
        }
        if (catalog.routes() == null || catalog.routes().isEmpty()) {
            throw new IllegalArgumentException("HTTP Route Catalog routes 不能为空");
        }

        Set<String> operationIds = new HashSet<>();
        Set<String> methodPaths = new HashSet<>();
        List<HttpRouteCatalog.Route> sorted = new ArrayList<>(catalog.routes());
        sorted.sort(Comparator.comparing((HttpRouteCatalog.Route route) -> route.method().name())
                .thenComparing(HttpRouteCatalog.Route::path)
                .thenComparing(HttpRouteCatalog.Route::operationId));
        if (!sorted.equals(catalog.routes())) {
            throw new IllegalArgumentException("HTTP Route Catalog routes 必须确定性排序");
        }

        for (HttpRouteCatalog.Route route : catalog.routes()) {
            if (route == null
                    || route.operationId() == null
                    || !OPERATION_ID.matcher(route.operationId()).matches()
                    || route.method() == null
                    || route.path() == null
                    || !route.path().startsWith("/")
                    || route.path().contains("?")
                    || route.serviceId() == null
                    || !SERVICE_ID.matcher(route.serviceId()).matches()
                    || route.credentialRequirement() == null
                    || route.requiredScopes() == null) {
                throw new IllegalArgumentException("HTTP Route Catalog route 字段非法");
            }
            if (!operationIds.add(route.operationId())) {
                throw new IllegalArgumentException("HTTP Route Catalog operationId 重复");
            }
            String normalizedPath = PATH_VARIABLE.matcher(route.path()).replaceAll("{}");
            if (!methodPaths.add(route.method() + " " + normalizedPath)) {
                throw new IllegalArgumentException("HTTP Route Catalog method/path 冲突");
            }
            validateScopes(route);
        }
    }

    private static void validateScopes(HttpRouteCatalog.Route route) {
        List<String> scopes = route.requiredScopes();
        List<String> sorted = scopes.stream().sorted().toList();
        if (!scopes.equals(sorted) || new HashSet<>(scopes).size() != scopes.size()) {
            throw new IllegalArgumentException("HTTP Route Catalog requiredScopes 必须去重并排序");
        }
        if (scopes.stream().anyMatch(scope -> scope == null || !SCOPE.matcher(scope).matches())) {
            throw new IllegalArgumentException("HTTP Route Catalog requiredScopes 非法");
        }
        if (route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED
                != !scopes.isEmpty()) {
            throw new IllegalArgumentException("HTTP Route Catalog credentialRequirement 与 requiredScopes 不一致");
        }
    }
}
