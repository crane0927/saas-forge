import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从正式 OpenAPI 契约生成 Gateway 运行时路由与 User Token security 元数据。 */
public final class GatewayOpenApiRouteMetadataGenerator {
    private static final Pattern PATH = Pattern.compile("^  (/[^:]+):$");
    private static final Pattern METHOD = Pattern.compile("^    (get|post|put|patch|delete|head|options|trace):$");
    private static final Pattern OWNER = Pattern.compile("^      x-saasforge-service: ([a-z-]+)$");
    private static final Pattern OPERATION_ID = Pattern.compile("^      operationId: ([A-Za-z][A-Za-z0-9]*)$");
    private static final Pattern SECURITY = Pattern.compile("^      security:(.*)$");

    private GatewayOpenApiRouteMetadataGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("需要 OpenAPI 输入路径和生成资源输出路径");
        }
        List<Route> routes = parse(Path.of(args[0]));
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# operationId\tmethod\tpath\ttarget\tuserTokenRequirement");
        routes.stream().map(Route::encoded).forEach(lines::add);
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    private static List<Route> parse(Path spec) throws IOException {
        String path = null;
        String method = null;
        String owner = null;
        String operationId = null;
        boolean explicitSecurity = false;
        boolean userBearer = false;
        boolean anonymousAlternative = false;
        boolean readingSecurity = false;
        List<Route> routes = new ArrayList<>();

        for (String line : Files.readAllLines(spec, StandardCharsets.UTF_8)) {
            Matcher pathMatcher = PATH.matcher(line);
            if (pathMatcher.matches()) {
                if (method != null) {
                    routes.add(route(path, method, owner, operationId,
                            explicitSecurity, userBearer, anonymousAlternative));
                    method = null;
                }
                path = pathMatcher.group(1);
                continue;
            }

            Matcher methodMatcher = METHOD.matcher(line);
            if (methodMatcher.matches()) {
                if (method != null) {
                    routes.add(route(path, method, owner, operationId,
                            explicitSecurity, userBearer, anonymousAlternative));
                }
                method = methodMatcher.group(1);
                owner = null;
                operationId = null;
                explicitSecurity = false;
                userBearer = false;
                anonymousAlternative = false;
                readingSecurity = false;
                continue;
            }

            if (method == null) {
                continue;
            }
            if (readingSecurity) {
                if (line.startsWith("        - ")) {
                    userBearer |= line.contains("UserBearerAuth");
                    anonymousAlternative |= line.trim().equals("- {}");
                    continue;
                }
                readingSecurity = false;
            }
            Matcher operationIdMatcher = OPERATION_ID.matcher(line);
            if (operationIdMatcher.matches()) {
                operationId = operationIdMatcher.group(1);
                continue;
            }
            Matcher ownerMatcher = OWNER.matcher(line);
            if (ownerMatcher.matches()) {
                owner = ownerMatcher.group(1);
                continue;
            }
            Matcher securityMatcher = SECURITY.matcher(line);
            if (securityMatcher.matches()) {
                explicitSecurity = true;
                String inlineSecurity = securityMatcher.group(1);
                userBearer |= inlineSecurity.contains("UserBearerAuth");
                anonymousAlternative |= inlineSecurity.contains("{}");
                readingSecurity = inlineSecurity.isBlank();
            }
        }
        if (method != null) {
            routes.add(route(path, method, owner, operationId,
                    explicitSecurity, userBearer, anonymousAlternative));
        }
        return List.copyOf(routes);
    }

    private static Route route(
            String path,
            String method,
            String owner,
            String operationId,
            boolean explicitSecurity,
            boolean userBearer,
            boolean anonymousAlternative) {
        if (path == null || method == null || owner == null || operationId == null) {
            throw new IllegalArgumentException("OpenAPI operation 缺少 path、method、operationId 或 owner");
        }
        String requirement = !explicitSecurity || !userBearer
                ? "NONE"
                : anonymousAlternative ? "OPTIONAL" : "REQUIRED";
        return new Route(
                operationId,
                method.toUpperCase(Locale.ROOT),
                path,
                target(owner),
                requirement);
    }

    private static String target(String owner) {
        return switch (owner) {
            case "iam-service" -> "IAM";
            case "tenant-access-service" -> "TENANT_ACCESS";
            case "entitlement-service" -> "ENTITLEMENT";
            default -> throw new IllegalArgumentException("没有 Gateway Target 的服务 owner: " + owner);
        };
    }

    private record Route(
            String operationId,
            String method,
            String path,
            String target,
            String userTokenRequirement) {

        private String encoded() {
            return String.join("\t", operationId, method, path, target, userTokenRequirement);
        }
    }
}
