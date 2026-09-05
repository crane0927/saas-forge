import { spawn, spawnSync } from "node:child_process";
import { constants } from "node:fs";
import {
  access,
  chmod,
  mkdir,
  open,
  readFile,
  readdir,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { createPrivateKey } from "node:crypto";
import { createConnection, createServer, isIP } from "node:net";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const supportedService = "iam-service";
const localHttpPort = 8081;
const localGrpcPort = 9091;
const tenantAccessGrpcPort = 9092;
const entitlementGrpcPort = 9093;
const localHost = "127.0.0.1";
const dockerHost = "host.docker.internal";
const timeoutMilliseconds = 90_000;
const localIamCallers = ["tenant-access-service", "entitlement-service"];
const additionalServiceDefinitions = Object.freeze({
  "audit-service": {
    artifact: "audit-service",
    configDataId: "audit-service.yaml",
    dependencies: ["gateway", "iam-service", "kafka", "nacos", "postgres"],
    grpcPort: undefined,
    httpPort: 8084,
    migration: "audit-migrate",
    module: "services/audit-service",
    nacosPassword: "NACOS_AUDIT_PASSWORD",
    nacosUsername: "NACOS_AUDIT_USERNAME",
    service: "audit-service",
    secretTargets: [],
  },
  "entitlement-service": {
    artifact: "entitlement-service",
    configDataId: "entitlement-service.yaml",
    dependencies: [
      "iam-service",
      "nacos",
      "postgres",
      "redis",
      "tenant-access-service",
    ],
    grpcPort: 9093,
    httpPort: 8083,
    migration: "entitlement-migrate",
    module: "services/entitlement-service",
    nacosPassword: "NACOS_ENTITLEMENT_PASSWORD",
    nacosUsername: "NACOS_ENTITLEMENT_USERNAME",
    service: "entitlement-service",
    secretTargets: [
      "/run/secrets/service-client-id",
      "/run/secrets/service-client-secret",
    ],
  },
  gateway: {
    artifact: "gateway",
    configDataId: "gateway.yaml",
    dependencies: [
      "audit-service",
      "entitlement-service",
      "iam-service",
      "nacos",
      "redis",
      "tenant-access-service",
    ],
    grpcPort: undefined,
    httpPort: 8080,
    migration: undefined,
    module: "gateway",
    nacosPassword: "NACOS_GATEWAY_PASSWORD",
    nacosUsername: "NACOS_GATEWAY_USERNAME",
    service: "gateway",
    secretTargets: [],
  },
  "tenant-access-service": {
    artifact: "tenant-access-service",
    configDataId: "tenant-access-service.yaml",
    dependencies: [
      "entitlement-service",
      "iam-service",
      "kafka",
      "nacos",
      "postgres",
      "redis",
    ],
    grpcPort: 9092,
    httpPort: 8082,
    migration: "tenant-access-migrate",
    module: "services/tenant-access-service",
    nacosPassword: "NACOS_TENANT_ACCESS_PASSWORD",
    nacosUsername: "NACOS_TENANT_ACCESS_USERNAME",
    service: "tenant-access-service",
    secretTargets: [
      "/run/secrets/service-client-id",
      "/run/secrets/service-client-secret",
      "/run/secrets/iam-service-client-id",
    ],
  },
});
const supportedServices = new Set([
  "iam-service",
  ...Object.keys(additionalServiceDefinitions),
]);
export const localDevelopmentServices = Object.freeze([
  "gateway",
  "iam-service",
  "tenant-access-service",
  "entitlement-service",
  "audit-service",
]);

class BlockedError extends Error {}

export function assertSupportedService(service) {
  if (!supportedServices.has(service)) {
    throw new BlockedError(
      "服务目标必须显式为 gateway、iam-service、tenant-access-service、entitlement-service 或 audit-service，且不能使用别名。",
    );
  }
}

export function additionalServiceDefinition(service) {
  const definition = additionalServiceDefinitions[service];
  if (definition === undefined) {
    throw new BlockedError(`${service} 不是其余后端服务的本机替换目标。`);
  }
  return definition;
}

export function additionalExecutableJar(files, artifact) {
  const jars = files
    .filter((file) => new RegExp(`^${artifact}-.+\\.jar$`, "u").test(file))
    .filter((file) => !file.startsWith("original-"))
    // Maven test-jar 使用固定分类器；本机 JVM 只能启动主制品。
    .filter((file) => !file.endsWith("-test-fixture.jar"))
    .sort();
  return jars.length === 1 ? jars[0] : undefined;
}

export function reusableContainerStartArguments(entry) {
  // 只接受 Docker 返回的十六进制容器 ID，避免 Compose 状态字段成为命令参数。
  if (typeof entry?.ID !== "string" || !/^[a-f0-9]{12,64}$/u.test(entry.ID)) {
    return undefined;
  }
  return ["start", entry.ID];
}

export function parseComposePs(output) {
  return output
    .split(/\r?\n/u)
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line));
}

export function classifyServiceState({
  containerRunning,
  localProcessRunning,
  localReady,
  localRegistered,
  healthyInstances,
}) {
  if (healthyInstances > 1 || (containerRunning && localProcessRunning)) {
    return "DUPLICATE";
  }
  if (healthyInstances === 1 && containerRunning && !localProcessRunning) {
    return "CONTAINER";
  }
  if (
    healthyInstances === 1 &&
    !containerRunning &&
    localProcessRunning &&
    localReady &&
    localRegistered
  ) {
    return "LOCAL";
  }
  return "UNAVAILABLE";
}

export function formatServiceStatus({
  service,
  state,
  httpPort,
  grpcPort,
  healthyInstances,
}) {
  const ports = [`http=${httpPort}`];
  if (grpcPort !== undefined) ports.push(`grpc=${grpcPort}`);
  const readiness =
    state === "CONTAINER" || state === "LOCAL" ? "READY" : "NOT_READY";
  return `STATUS: ${service} ${state} ${ports.join(" ")} readiness=${readiness} nacos=${healthyInstances}`;
}

function unavailableServiceStatus(service) {
  const definition =
    service === supportedService
      ? {
          service,
          httpPort: localHttpPort,
          grpcPort: localGrpcPort,
        }
      : additionalServiceDefinition(service);
  return formatServiceStatus({
    service: definition.service,
    state: "UNAVAILABLE",
    httpPort: definition.httpPort,
    grpcPort: definition.grpcPort,
    healthyInstances: "UNAVAILABLE",
  });
}

export function nacosHosts(payload) {
  const hosts = Array.isArray(payload?.data)
    ? payload.data
    : (payload?.data?.hosts ?? payload?.hosts);
  if (!Array.isArray(hosts)) return [];
  return hosts
    .filter((host) => host.healthy !== false && host.enabled !== false)
    .map((host) => ({ ip: host.ip, port: Number(host.port) }))
    .filter((host) => isIP(host.ip) === 4 && Number.isInteger(host.port));
}

export function assertGrpcHostPortPlan({ iam, tenantAccess, entitlement }) {
  const assignments = [
    ["iam-service", iam, localGrpcPort],
    ["tenant-access-service", tenantAccess, tenantAccessGrpcPort],
    ["entitlement-service", entitlement, entitlementGrpcPort],
  ];
  for (const [service, port, expectedPort] of assignments) {
    if (!Number.isInteger(port) || port !== expectedPort) {
      throw new BlockedError(
        `${service} 必须将容器 gRPC 9090 固定发布为回环 ${expectedPort}。`,
      );
    }
  }
  if (
    new Set(assignments.map(([, port]) => port)).size !== assignments.length
  ) {
    throw new BlockedError("本机 gRPC 端口映射必须唯一。");
  }
}

export function localIamCallerEnvironment() {
  return {
    "entitlement-service": {
      IAM_GRPC_ADDRESS: `static://${dockerHost}:${localGrpcPort}`,
      IAM_HTTP_BASE_URL: `http://${dockerHost}:${localHttpPort}`,
    },
    "tenant-access-service": {
      IAM_GRPC_ADDRESS: `static://${dockerHost}:${localGrpcPort}`,
      IAM_HTTP_BASE_URL: `http://${dockerHost}:${localHttpPort}`,
    },
  };
}

export function callersAreReady(instancesByService) {
  return localIamCallers.every(
    (service) => instancesByService[service]?.length === 1,
  );
}

export function localIamEnvironment(inputs, dockerHostAddress) {
  return {
    BROWSER_ROOT_DOMAIN: inputs.environment.BROWSER_ROOT_DOMAIN,
    IAM_HTTP_BASE_URL: `http://${localHost}:${localHttpPort}`,
    IAM_JWT_ISSUER: inputs.environment.IAM_JWT_ISSUER,
    IAM_JWT_PEM_KEY_VERSION_REF: inputs.environment.IAM_JWT_PEM_KEY_VERSION_REF,
    IAM_JWT_PEM_PRIVATE_KEY_LOCATION: `file:${inputs.signingKeyFile}`,
    KAFKA_BOOTSTRAP_SERVERS: `${localHost}:29092`,
    NACOS_IAM_PASSWORD: inputs.environment.NACOS_IAM_PASSWORD,
    NACOS_IAM_USERNAME: inputs.environment.NACOS_IAM_USERNAME,
    NACOS_NAMESPACE: "dev",
    NACOS_SERVER_ADDR: `${localHost}:${inputs.nacosPort}`,
    NACOS_TLS_ENABLED: "false",
    PASSWORD_SETUP_PAGE_URI: inputs.environment.PASSWORD_SETUP_PAGE_URI,
    SAASFORGE_ENVIRONMENT: "dev",
    SAASFORGE_SERVICE_CLIENT_ID_FILE: inputs.serviceClientIdFile,
    SAASFORGE_SERVICE_CLIENT_SECRET_FILE: inputs.serviceClientSecretFile,
    SERVER_ADDRESS: "0.0.0.0",
    SERVER_PORT: String(localHttpPort),
    SPRING_GRPC_SERVER_PORT: String(inputs.grpcPorts.iam),
    SMTP_FROM: inputs.environment.SMTP_FROM,
    SMTP_HOST: localHost,
    SMTP_PORT: "1025",
    SPRING_CLOUD_NACOS_DISCOVERY_IP: dockerHostAddress,
    SPRING_CLOUD_NACOS_DISCOVERY_PORT: String(localHttpPort),
    SPRING_DATA_REDIS_HOST: localHost,
    SPRING_DATA_REDIS_PASSWORD: inputs.environment.SPRING_DATA_REDIS_PASSWORD,
    SPRING_DATA_REDIS_PORT: "6379",
    SPRING_DATASOURCE_PASSWORD: inputs.environment.SPRING_DATASOURCE_PASSWORD,
    SPRING_DATASOURCE_URL: `jdbc:postgresql://${localHost}:5432/iam_db`,
    SPRING_DATASOURCE_USERNAME: inputs.environment.SPRING_DATASOURCE_USERNAME,
    TENANT_ACCESS_GRPC_ADDRESS: `static://${localHost}:${inputs.grpcPorts.tenantAccess}`,
  };
}

function rootDirectory() {
  return path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
}

function runtimePaths(root) {
  const directory = path.join(
    root,
    "deploy",
    "compose",
    ".secrets",
    "local-service-replacement",
  );
  return {
    directory,
    iamLocalCallersOverride: path.join(
      directory,
      "iam-local-callers.override.yaml",
    ),
    iamLog: path.join(directory, "iam-service.log"),
    iamPid: path.join(directory, "iam-service.pid"),
  };
}

function composeArguments(root) {
  const directory = path.join(root, "deploy", "compose");
  const arguments_ = ["compose", "--project-directory", directory];
  if (process.env.LOCAL_COMPOSE_ENV_FILE) {
    arguments_.push("--env-file", process.env.LOCAL_COMPOSE_ENV_FILE);
  }
  if (process.env.COMPOSE_PROJECT_NAME) {
    arguments_.push("--project-name", process.env.COMPOSE_PROJECT_NAME);
  }
  arguments_.push("--file", path.join(directory, "compose.yaml"));
  if (process.env.LOCAL_COMPOSE_OVERRIDE_FILE) {
    arguments_.push("--file", process.env.LOCAL_COMPOSE_OVERRIDE_FILE);
  }
  return { directory, arguments_ };
}

function execute(command, arguments_, { allowFailure = false, cwd, env } = {}) {
  const result = spawnSync(command, arguments_, {
    cwd,
    encoding: "utf8",
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if ((result.error || result.status !== 0) && !allowFailure) {
    throw new BlockedError(`无法完成 ${command} 调用；请检查本机开发环境。`);
  }
  return { status: result.status ?? 1, stdout: result.stdout ?? "" };
}

function compose(context, ...arguments_) {
  return execute("docker", [...context.compose.arguments_, ...arguments_], {
    cwd: context.compose.directory,
  });
}

function composeWithIamLocalCallers(context, paths, ...arguments_) {
  return execute(
    "docker",
    [
      ...context.compose.arguments_,
      "--file",
      paths.iamLocalCallersOverride,
      ...arguments_,
    ],
    { cwd: context.compose.directory },
  );
}

function iamLocalCallersOverride() {
  return [
    "services:",
    ...Object.entries(localIamCallerEnvironment()).flatMap(
      ([service, environment]) => [
        `  ${service}:`,
        "    environment:",
        ...Object.entries(environment).map(
          ([name, value]) => `      ${name}: \"${value}\"`,
        ),
      ],
    ),
    "",
  ].join("\n");
}

async function hasIamLocalCallersOverride(paths) {
  try {
    await access(paths.iamLocalCallersOverride, constants.R_OK);
    return true;
  } catch {
    return false;
  }
}

async function waitForCallersReady(context, token, description) {
  await waitFor(description, async () => {
    const [entries, registrations] = await Promise.all([
      composeEntries(context),
      Promise.all(
        localIamCallers.map(async (service) => [
          service,
          await healthyInstances(context, token, service),
        ]),
      ),
    ]);
    // Compose 的 running 状态不代表 Gateway 已能从 Nacos 选择调用方实例。
    return (
      localIamCallers.every((service) =>
        isRunning(stateOf(entries, service)),
      ) && callersAreReady(Object.fromEntries(registrations))
    );
  });
}

async function routeContainerCallersToLocalIam(context, paths, token) {
  await writeFile(paths.iamLocalCallersOverride, iamLocalCallersOverride(), {
    mode: 0o600,
  });
  composeWithIamLocalCallers(context, paths, "config", "--quiet");
  composeWithIamLocalCallers(
    context,
    paths,
    "up",
    "--detach",
    "--no-deps",
    "--force-recreate",
    ...localIamCallers,
  );
  await waitForCallersReady(context, token, "容器调用方按本机 IAM 路由重建");
}

async function restoreContainerCallerRoutes(context, paths, token) {
  if (!(await hasIamLocalCallersOverride(paths))) return;
  await rm(paths.iamLocalCallersOverride, { force: true });
  compose(
    context,
    "up",
    "--detach",
    "--no-deps",
    "--force-recreate",
    ...localIamCallers,
  );
  await waitForCallersReady(context, token, "容器调用方按默认 IAM 路由重建");
}

function environmentValue(environment, name, service = supportedService) {
  const value = Array.isArray(environment)
    ? environment
        .find((candidate) => candidate.startsWith(`${name}=`))
        ?.slice(name.length + 1)
    : environment?.[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new BlockedError(`${service} 缺少必需环境变量 ${name}。`);
  }
  return value;
}

function volumeSource(service, target, serviceName = supportedService) {
  const volume = service.volumes?.find(
    (candidate) => typeof candidate === "object" && candidate.target === target,
  );
  if (typeof volume?.source !== "string" || volume.source.length === 0) {
    throw new BlockedError(`${serviceName} 缺少受限 Secret 挂载 ${target}。`);
  }
  return volume.source;
}

function publishedPort(service, targetPort) {
  for (const port of service.ports ?? []) {
    if (typeof port === "object" && Number(port.target) === targetPort) {
      const published = Number(port.published);
      if (Number.isInteger(published)) return published;
    }
    if (typeof port === "string") {
      const fields = port.replace(/\/(?:tcp|udp)$/u, "").split(":");
      if (Number(fields.at(-1)) === targetPort) {
        const published = Number(fields.at(-2));
        if (Number.isInteger(published)) return published;
      }
    }
  }
  throw new BlockedError(`Nacos 未向本机发布 ${targetPort} 端口。`);
}

function loadContext(root) {
  const compose_ = composeArguments(root);
  const configuration = compose(
    { compose: compose_ },
    "config",
    "--format",
    "json",
  );
  let document;
  try {
    document = JSON.parse(configuration.stdout);
  } catch {
    throw new BlockedError(
      "无法解析本地 Compose 配置。请先修复 deploy/compose/.env。",
    );
  }
  const iam = document.services?.[supportedService];
  const gateway = document.services?.gateway;
  const nacos = document.services?.nacos;
  const tenantAccess = document.services?.["tenant-access-service"];
  const entitlement = document.services?.["entitlement-service"];
  if (!iam || !gateway || !nacos || !tenantAccess || !entitlement) {
    throw new BlockedError(
      "Compose 配置缺少 iam-service、tenant-access-service、entitlement-service、gateway 或 nacos。",
    );
  }
  const grpcPorts = {
    entitlement: publishedPort(entitlement, 9090),
    iam: publishedPort(iam, 9090),
    tenantAccess: publishedPort(tenantAccess, 9090),
  };
  assertGrpcHostPortPlan(grpcPorts);
  if (publishedPort(iam, 8080) !== localHttpPort) {
    throw new BlockedError(
      `iam-service 必须将 HTTP 8080 固定发布为回环 ${localHttpPort}。`,
    );
  }
  const environment = iam.environment;
  return {
    compose: compose_,
    environment: Object.fromEntries(
      [
        "BROWSER_ROOT_DOMAIN",
        "IAM_JWT_ISSUER",
        "IAM_JWT_PEM_KEY_VERSION_REF",
        "NACOS_IAM_PASSWORD",
        "NACOS_IAM_USERNAME",
        "PASSWORD_SETUP_PAGE_URI",
        "SMTP_FROM",
        "SPRING_DATA_REDIS_PASSWORD",
        "SPRING_DATASOURCE_PASSWORD",
        "SPRING_DATASOURCE_USERNAME",
      ].map((name) => [name, environmentValue(environment, name)]),
    ),
    gatewayDiscovery: {
      password: environmentValue(gateway.environment, "NACOS_GATEWAY_PASSWORD"),
      username: environmentValue(gateway.environment, "NACOS_GATEWAY_USERNAME"),
    },
    grpcPorts,
    nacosGrpcPort: publishedPort(nacos, 9848),
    nacosPort: publishedPort(nacos, 8848),
    root,
    serviceClientIdFile: volumeSource(iam, "/run/secrets/service-client-id"),
    serviceClientSecretFile: volumeSource(
      iam,
      "/run/secrets/service-client-secret",
    ),
    signingKeyFile: volumeSource(iam, "/run/secrets/iam-jwt-private-key.pem"),
  };
}

async function assertReadableNonEmpty(...files) {
  for (const file of files) {
    try {
      await access(file, constants.R_OK);
      if ((await stat(file)).size < 1) throw new Error("empty");
    } catch {
      throw new BlockedError(`必需 Secret 文件不可读或为空：${file}`);
    }
  }
}

export function stateOf(entries, service) {
  const matching = entries.filter((entry) => entry.Service === service);
  return matching.find(isRunning) ?? matching.at(0);
}

function isRunning(entry) {
  return entry?.State === "running";
}

function hasPublishedPort(entry, targetPort, publishedPort) {
  return (entry?.Publishers ?? []).some(
    (publisher) =>
      Number(publisher.TargetPort) === targetPort &&
      Number(publisher.PublishedPort) === publishedPort &&
      publisher.URL === localHost,
  );
}

async function composeEntries(context) {
  const result = compose(context, "ps", "--all", "--format", "json");
  try {
    return parseComposePs(result.stdout);
  } catch {
    throw new BlockedError("无法解析 Compose 运行状态。");
  }
}

async function assertInfrastructure(context) {
  const entries = await composeEntries(context);
  const migration = stateOf(entries, "iam-migrate");
  if (migration?.State !== "exited" || Number(migration.ExitCode) !== 0) {
    throw new BlockedError(
      "IAM Flyway 迁移任务未成功结束；请先修复 iam-migrate。",
    );
  }
  for (const service of ["postgres", "redis", "kafka", "mailpit", "nacos"]) {
    const entry = stateOf(entries, service);
    if (!isRunning(entry) || (entry.Health && entry.Health !== "healthy")) {
      throw new BlockedError(`${service} 未处于健康运行状态。`);
    }
  }
}

function nacosBaseUrl(context) {
  return `http://${localHost}:${context.nacosPort}/nacos`;
}

async function nacosLogin(context, username, password) {
  let response;
  try {
    response = await fetch(`${nacosBaseUrl(context)}/v3/auth/user/login`, {
      body: new URLSearchParams({ username, password }),
      method: "POST",
      signal: AbortSignal.timeout(5_000),
    });
  } catch {
    throw new BlockedError("Nacos 不可达或身份验证请求失败。");
  }
  const payload = await response.json().catch(() => undefined);
  const token = payload?.accessToken ?? payload?.data?.accessToken;
  if (!response.ok || typeof token !== "string" || token.length === 0) {
    throw new BlockedError("Nacos 身份验证失败；请检查受限开发凭据。");
  }
  return token;
}

async function nacosRequest(
  context,
  token,
  pathname,
  parameters,
  { emptyInstanceListOnNotFound = false } = {},
) {
  const url = new URL(`${nacosBaseUrl(context)}${pathname}`);
  for (const [name, value] of Object.entries(parameters)) {
    url.searchParams.set(name, value);
  }
  let response;
  try {
    response = await fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(5_000),
    });
  } catch {
    throw new BlockedError("Nacos 查询失败。");
  }
  const payload = await response.json().catch(() => undefined);
  if (
    emptyInstanceListOnNotFound &&
    response.status === 404 &&
    payload?.code === 30000
  ) {
    return { data: [] };
  }
  if (!response.ok || payload?.code !== 0) {
    throw new BlockedError("Nacos 拒绝当前生命周期查询。");
  }
  return payload;
}

async function assertNacosConfiguration(context) {
  const token = await nacosLogin(
    context,
    context.environment.NACOS_IAM_USERNAME,
    context.environment.NACOS_IAM_PASSWORD,
  );
  await nacosRequest(context, token, "/v3/client/cs/config", {
    dataId: "iam-service.yaml",
    groupName: "SAAS_FORGE",
    namespaceId: "dev",
  });
}

async function gatewayDiscoveryToken(context) {
  // 工作负载身份只有自身注册权限；生命周期状态使用既有 Gateway 只读发现身份查询。
  return nacosLogin(
    context,
    context.gatewayDiscovery.username,
    context.gatewayDiscovery.password,
  );
}

async function healthyInstances(context, token, service = supportedService) {
  const payload = await nacosRequest(
    context,
    token,
    "/v3/admin/ns/instance/list",
    {
      groupName: "DEFAULT_GROUP",
      healthyOnly: "true",
      namespaceId: "dev",
      serviceName: service,
    },
    { emptyInstanceListOnNotFound: true },
  );
  return nacosHosts(payload);
}

async function dockerHostAddress(context) {
  const result = compose(
    context,
    "exec",
    "-T",
    "nacos",
    "sh",
    "-c",
    "getent ahostsv4 host.docker.internal 2>/dev/null || nslookup host.docker.internal 2>/dev/null || true",
  );
  const address = result.stdout
    .split(/\s+/u)
    .find((candidate) => isIP(candidate) === 4);
  if (!address) {
    throw new BlockedError(
      "Docker Desktop 未提供 host.docker.internal 的可达 IPv4 地址。",
    );
  }
  return address;
}

async function verifySigningKey(context) {
  let key;
  try {
    key = createPrivateKey(await readFile(context.signingKeyFile));
  } catch {
    throw new BlockedError("IAM Signing Key 文件不是可用的私钥。");
  }
  const publicJwk = key.export({ format: "jwk" });
  const result = compose(
    context,
    "exec",
    "-T",
    "postgres",
    "sh",
    "-ec",
    "psql --username \"$POSTGRES_USER\" --dbname iam_db --tuples-only --no-align --field-separator '\t' --command \"SELECT key_version_reference, public_jwk_modulus, public_jwk_exponent FROM iam_signing_keys WHERE key_status = 'ACTIVE'\"",
  );
  const rows = result.stdout
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
  if (rows.length !== 1) {
    throw new BlockedError(
      "IAM 数据库必须存在且仅存在一个 ACTIVE Signing Key。",
    );
  }
  const [versionReference, modulus, exponent] = rows[0].split("\t");
  if (
    versionReference !== context.environment.IAM_JWT_PEM_KEY_VERSION_REF ||
    modulus !== publicJwk.n ||
    exponent !== publicJwk.e
  ) {
    throw new BlockedError(
      "IAM ACTIVE Signing Key 与受限本地私钥或 Key Version 不匹配。",
    );
  }
}

async function preflight(context) {
  await assertInfrastructure(context);
  await assertReadableNonEmpty(
    context.signingKeyFile,
    context.serviceClientIdFile,
    context.serviceClientSecretFile,
  );
  await verifySigningKey(context);
  await assertNacosConfiguration(context);
}

async function localProcess(paths) {
  const pid = Number.parseInt(
    await readFile(paths.iamPid, "utf8").catch(() => ""),
    10,
  );
  if (!Number.isInteger(pid)) return undefined;
  const result = execute("ps", ["-p", String(pid), "-o", "command="], {
    allowFailure: true,
  });
  if (result.status !== 0) return undefined;
  if (!result.stdout.includes("/services/iam-service/target/iam-service-")) {
    throw new BlockedError(
      "本地 IAM PID 文件未指向受管 IAM 进程；拒绝操作未知进程。",
    );
  }
  return pid;
}

async function localReadiness() {
  const response = await fetch(
    `http://${localHost}:${localHttpPort}/actuator/health/readiness`,
    { signal: AbortSignal.timeout(1_000) },
  ).catch(() => undefined);
  return response?.status === 200;
}

async function assertPortAvailable(port) {
  await new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", () => {
      reject(
        new BlockedError(`本机端口 ${port} 已被占用；请释放后再替换 IAM。`),
      );
    });
    server.listen({ exclusive: true, host: localHost, port }, () => {
      server.close((error) => {
        if (error) {
          reject(new BlockedError(`无法检查本机端口 ${port}。`));
          return;
        }
        resolve();
      });
    });
  });
}

async function assertLocalIamPortsAvailable() {
  await assertPortAvailable(localHttpPort);
  await assertPortAvailable(localGrpcPort);
}

async function observe(context, paths, token) {
  const [entries, pid, instances, hostAddress] = await Promise.all([
    composeEntries(context),
    localProcess(paths),
    healthyInstances(context, token),
    dockerHostAddress(context),
  ]);
  const localReady = pid !== undefined && (await localReadiness());
  const localRegistered = instances.some(
    (instance) =>
      instance.ip === hostAddress && instance.port === localHttpPort,
  );
  const containerRunning = isRunning(stateOf(entries, supportedService));
  return {
    hostAddress,
    instances,
    pid,
    state: classifyServiceState({
      containerRunning,
      healthyInstances: instances.length,
      localProcessRunning: pid !== undefined,
      localReady,
      localRegistered,
    }),
  };
}

async function waitFor(description, condition) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    if (await condition()) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new BlockedError(`${description} 未在 90 秒内满足。`);
}

function systemEnvironment() {
  const names = [
    "HOME",
    "JAVA_HOME",
    "LANG",
    "LC_ALL",
    "MISE_CONFIG_ROOT",
    "MISE_DATA_DIR",
    "PATH",
    "SHELL",
    "TMPDIR",
  ];
  return Object.fromEntries(
    names
      .filter((name) => process.env[name] !== undefined)
      .map((name) => [name, process.env[name]]),
  );
}

async function packageLocalIam(context) {
  execute(
    path.join(context.root, "mvnw"),
    [
      "--batch-mode",
      "--no-transfer-progress",
      "-pl",
      "services/iam-service",
      "-am",
      "package",
      "-DskipTests",
    ],
    { cwd: context.root, env: systemEnvironment() },
  );
  const target = path.join(context.root, "services", "iam-service", "target");
  const jars = (await readdir(target))
    .filter((file) => /^iam-service-.+\.jar$/u.test(file))
    .filter((file) => !file.startsWith("original-"))
    .sort();
  if (jars.length !== 1) {
    throw new BlockedError("无法确定本机 IAM 可执行 JAR。");
  }
  return path.join(target, jars[0]);
}

async function startLocalIam(context, paths, jar, hostAddress) {
  await mkdir(paths.directory, { recursive: true, mode: 0o700 });
  await chmod(paths.directory, 0o700);
  const log = await open(paths.iamLog, "a", 0o600);
  const child = spawn("java", ["-jar", jar], {
    cwd: context.root,
    detached: true,
    env: {
      ...systemEnvironment(),
      ...localIamEnvironment(context, hostAddress),
    },
    stdio: ["ignore", log.fd, log.fd],
  });
  await log.close();
  if (!child.pid) {
    throw new BlockedError("无法启动本机 IAM 进程。");
  }
  child.unref();
  await writeFile(paths.iamPid, `${child.pid}\n`, { mode: 0o600 });
}

async function stopLocalIam(paths) {
  const pid = await localProcess(paths);
  if (pid === undefined) {
    await rm(paths.iamPid, { force: true });
    return;
  }
  process.kill(pid, "SIGTERM");
  await waitFor("本机 IAM 退出", async () => processHasExited(pid));
  await rm(paths.iamPid, { force: true });
}

function processHasExited(pid) {
  const result = execute("ps", ["-p", String(pid), "-o", "state="], {
    allowFailure: true,
  });
  return result.status !== 0 || result.stdout.trim().startsWith("Z");
}

async function ensureContainer(context, paths, token) {
  await stopLocalIam(paths);
  const entries = await composeEntries(context);
  if (!isRunning(stateOf(entries, supportedService))) {
    await waitFor(
      "本机 IAM 从 Nacos 摘除",
      async () => (await healthyInstances(context, token)).length === 0,
    );
  }
  compose(
    context,
    "up",
    "--detach",
    "--no-deps",
    "--force-recreate",
    supportedService,
  );
  await waitFor(
    "容器 IAM 就绪",
    async () => (await observe(context, paths, token)).state === "CONTAINER",
  );
  await restoreContainerCallerRoutes(context, paths, token);
}

async function replace(context, paths) {
  await preflight(context);
  const token = await gatewayDiscoveryToken(context);
  const current = await observe(context, paths, token);
  if (current.state === "LOCAL") {
    if (!(await hasIamLocalCallersOverride(paths))) {
      throw new BlockedError(
        "检测到旧版本本机 IAM；请先运行 restore iam-service，再重新运行 replace iam-service。",
      );
    }
    console.log("REPLACE: iam-service 已处于本机运行，未重复启动。");
    return;
  }
  if (current.state === "DUPLICATE") {
    throw new BlockedError("Nacos 中存在多个健康 IAM 实例；拒绝替换。");
  }
  if (current.state !== "CONTAINER") {
    throw new BlockedError("IAM 未处于可替换的容器状态。请先运行 status。");
  }
  const jar = await packageLocalIam(context);
  let containerStopped = false;
  try {
    compose(context, "stop", supportedService);
    containerStopped = true;
    await waitFor("容器 IAM 停止并从 Nacos 摘除", async () => {
      const entries = await composeEntries(context);
      return (
        !isRunning(stateOf(entries, supportedService)) &&
        (await healthyInstances(context, token)).length === 0
      );
    });
    await assertLocalIamPortsAvailable();
    await startLocalIam(context, paths, jar, await dockerHostAddress(context));
    await waitFor(
      "本机 IAM 就绪且完成 Nacos 注册",
      async () => (await observe(context, paths, token)).state === "LOCAL",
    );
    await routeContainerCallersToLocalIam(context, paths, token);
  } catch (error) {
    // 本机替换失败时恢复标准 Compose 拓扑，避免把可用容器停在半完成状态。
    if (containerStopped) {
      try {
        await ensureContainer(context, paths, token);
        console.error("RECOVERY: 已恢复容器 IAM。");
      } catch {
        console.error(
          "RECOVERY: 自动恢复容器 IAM 失败；请运行 restore iam-service。",
        );
      }
    }
    throw error;
  }
  console.log("REPLACE: iam-service 已由本机 JVM 接管。");
}

async function restore(context, paths) {
  const token = await gatewayDiscoveryToken(context);
  const current = await observe(context, paths, token);
  if (current.state === "DUPLICATE") {
    throw new BlockedError(
      "Nacos 中存在多个健康 IAM 实例；拒绝在不明确状态下恢复。",
    );
  }
  if (current.state === "CONTAINER") {
    await stopLocalIam(paths);
    const entries = await composeEntries(context);
    const iam = stateOf(entries, supportedService);
    if (
      (await hasIamLocalCallersOverride(paths)) ||
      !hasPublishedPort(iam, 9090, context.grpcPorts.iam)
    ) {
      await ensureContainer(context, paths, token);
    }
    console.log("RESTORE: iam-service 已处于容器运行，未重复启动。");
    return;
  }
  await ensureContainer(context, paths, token);
  console.log("RESTORE: iam-service 已恢复为唯一健康容器实例。");
}

async function status(context, paths) {
  let observed;
  try {
    observed = await observe(
      context,
      paths,
      await gatewayDiscoveryToken(context),
    );
  } catch {
    console.log(
      formatServiceStatus({
        service: supportedService,
        state: "UNAVAILABLE",
        httpPort: localHttpPort,
        grpcPort: localGrpcPort,
        healthyInstances: "UNAVAILABLE",
      }),
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    formatServiceStatus({
      service: supportedService,
      state: observed.state,
      httpPort: localHttpPort,
      grpcPort: localGrpcPort,
      healthyInstances: observed.instances.length,
    }),
  );
  if (observed.state === "UNAVAILABLE" || observed.state === "DUPLICATE") {
    process.exitCode = 1;
  }
}

function additionalRuntimePaths(root, definition) {
  const directory = path.join(
    root,
    "deploy",
    "compose",
    ".secrets",
    "local-service-replacement",
  );
  return {
    apiTarget: path.join(directory, "api-target.json"),
    directory,
    log: path.join(directory, `${definition.service}.log`),
    pid: path.join(directory, `${definition.service}.pid`),
  };
}

export function localAdditionalEnvironment(definition, environment, inputs) {
  const nacos = {
    NACOS_NAMESPACE: "dev",
    NACOS_SERVER_ADDR: `${localHost}:${inputs.nacosPort}`,
    NACOS_TLS_ENABLED: "false",
    SERVER_ADDRESS: "0.0.0.0",
    SERVER_PORT: String(definition.httpPort),
    SPRING_CLOUD_NACOS_DISCOVERY_IP: inputs.dockerHostAddress,
    SPRING_CLOUD_NACOS_DISCOVERY_PORT: String(definition.httpPort),
  };
  if (definition.service === "gateway") {
    return {
      ...nacos,
      IAM_JWT_ISSUER: environment.IAM_JWT_ISSUER,
      NACOS_GATEWAY_PASSWORD: environment.NACOS_GATEWAY_PASSWORD,
      NACOS_GATEWAY_USERNAME: environment.NACOS_GATEWAY_USERNAME,
      SAASFORGE_ENVIRONMENT: "dev",
      SAASFORGE_LOCAL_REPLACEMENT_ENABLED: "true",
      SAASFORGE_LOCAL_REPLACEMENT_ENTITLEMENT_SERVICE_PORT: "8083",
      SAASFORGE_LOCAL_REPLACEMENT_IAM_SERVICE_PORT: "8081",
      SAASFORGE_LOCAL_REPLACEMENT_TENANT_ACCESS_SERVICE_PORT: "8082",
      SPRING_DATA_REDIS_HOST: localHost,
      SPRING_DATA_REDIS_PASSWORD: environment.SPRING_DATA_REDIS_PASSWORD,
      SPRING_DATA_REDIS_PORT: "6379",
    };
  }
  if (definition.service === "tenant-access-service") {
    return {
      ...nacos,
      ENTITLEMENT_GRPC_ADDRESS: `static://${localHost}:9093`,
      IAM_GRPC_ADDRESS: `static://${localHost}:9091`,
      IAM_HTTP_BASE_URL: `http://${localHost}:8081`,
      IAM_JWT_ISSUER: environment.IAM_JWT_ISSUER,
      IAM_SERVICE_CLIENT_ID_FILE:
        inputs.secretFiles["/run/secrets/iam-service-client-id"],
      KAFKA_BOOTSTRAP_SERVERS: `${localHost}:29092`,
      NACOS_TENANT_ACCESS_PASSWORD: environment.NACOS_TENANT_ACCESS_PASSWORD,
      NACOS_TENANT_ACCESS_USERNAME: environment.NACOS_TENANT_ACCESS_USERNAME,
      SAASFORGE_ENVIRONMENT: "dev",
      SAASFORGE_SERVICE_CLIENT_ID_FILE:
        inputs.secretFiles["/run/secrets/service-client-id"],
      SAASFORGE_SERVICE_CLIENT_SECRET_FILE:
        inputs.secretFiles["/run/secrets/service-client-secret"],
      SPRING_DATA_REDIS_HOST: localHost,
      SPRING_DATA_REDIS_PASSWORD: environment.SPRING_DATA_REDIS_PASSWORD,
      SPRING_DATA_REDIS_PORT: "6379",
      SPRING_DATASOURCE_PASSWORD: environment.SPRING_DATASOURCE_PASSWORD,
      SPRING_DATASOURCE_URL: `jdbc:postgresql://${localHost}:5432/tenant_access_db`,
      SPRING_DATASOURCE_USERNAME: environment.SPRING_DATASOURCE_USERNAME,
      SPRING_GRPC_SERVER_PORT: String(definition.grpcPort),
    };
  }
  if (definition.service === "entitlement-service") {
    return {
      ...nacos,
      IAM_GRPC_ADDRESS: `static://${localHost}:9091`,
      IAM_HTTP_BASE_URL: `http://${localHost}:8081`,
      IAM_JWT_ISSUER: environment.IAM_JWT_ISSUER,
      NACOS_ENTITLEMENT_PASSWORD: environment.NACOS_ENTITLEMENT_PASSWORD,
      NACOS_ENTITLEMENT_USERNAME: environment.NACOS_ENTITLEMENT_USERNAME,
      SAASFORGE_ENVIRONMENT: "dev",
      SAASFORGE_SERVICE_CLIENT_ID_FILE:
        inputs.secretFiles["/run/secrets/service-client-id"],
      SAASFORGE_SERVICE_CLIENT_SECRET_FILE:
        inputs.secretFiles["/run/secrets/service-client-secret"],
      SPRING_DATA_REDIS_HOST: localHost,
      SPRING_DATA_REDIS_PASSWORD: environment.SPRING_DATA_REDIS_PASSWORD,
      SPRING_DATA_REDIS_PORT: "6379",
      SPRING_DATASOURCE_PASSWORD: environment.SPRING_DATASOURCE_PASSWORD,
      SPRING_DATASOURCE_URL: `jdbc:postgresql://${localHost}:5432/entitlement_db`,
      SPRING_DATASOURCE_USERNAME: environment.SPRING_DATASOURCE_USERNAME,
      SPRING_GRPC_SERVER_PORT: String(definition.grpcPort),
      TENANT_ACCESS_GRPC_ADDRESS: `static://${localHost}:9092`,
    };
  }
  return {
    ...nacos,
    AUDIT_DATABASE_PASSWORD: environment.AUDIT_DATABASE_PASSWORD,
    AUDIT_DATABASE_URL: `jdbc:postgresql://${localHost}:5432/audit_db`,
    AUDIT_DATABASE_USERNAME: environment.AUDIT_DATABASE_USERNAME,
    KAFKA_BOOTSTRAP_SERVERS: `${localHost}:29092`,
    NACOS_AUDIT_PASSWORD: environment.NACOS_AUDIT_PASSWORD,
    NACOS_AUDIT_USERNAME: environment.NACOS_AUDIT_USERNAME,
    SAASFORGE_ENVIRONMENT: "dev",
  };
}

function additionalEnvironmentNames(definition) {
  const common = [definition.nacosPassword, definition.nacosUsername];
  if (definition.service === "gateway") {
    return [...common, "IAM_JWT_ISSUER", "SPRING_DATA_REDIS_PASSWORD"];
  }
  if (
    definition.service === "tenant-access-service" ||
    definition.service === "entitlement-service"
  ) {
    return [
      ...common,
      "IAM_JWT_ISSUER",
      "SPRING_DATA_REDIS_PASSWORD",
      "SPRING_DATASOURCE_PASSWORD",
      "SPRING_DATASOURCE_USERNAME",
    ];
  }
  return [...common, "AUDIT_DATABASE_PASSWORD", "AUDIT_DATABASE_USERNAME"];
}

function additionalContext(root, definition) {
  const compose_ = composeArguments(root);
  const configuration = compose(
    { compose: compose_ },
    "config",
    "--format",
    "json",
  );
  let document;
  try {
    document = JSON.parse(configuration.stdout);
  } catch {
    throw new BlockedError(
      "无法解析本地 Compose 配置。请先修复 deploy/compose/.env。",
    );
  }
  const services = document.services ?? {};
  const application = services[definition.service];
  const nacos = services.nacos;
  if (!application || !nacos) {
    throw new BlockedError(`Compose 配置缺少 ${definition.service} 或 nacos。`);
  }
  if (publishedPort(application, 8080) !== definition.httpPort) {
    throw new BlockedError(
      `${definition.service} 必须将 HTTP 8080 固定发布为回环 ${definition.httpPort}。`,
    );
  }
  if (
    definition.grpcPort !== undefined &&
    publishedPort(application, 9090) !== definition.grpcPort
  ) {
    throw new BlockedError(
      `${definition.service} 必须将 gRPC 9090 固定发布为回环 ${definition.grpcPort}。`,
    );
  }
  const fixedPorts = [
    ["postgres", 5432, 5432],
    ["redis", 6379, 6379],
    ["kafka", 29092, 29092],
    ["nacos", 8848, 8848],
    ["nacos", 9848, 9848],
  ];
  for (const [service, targetPort, expectedPort] of fixedPorts) {
    if (services[service] === undefined) {
      throw new BlockedError(`Compose 配置缺少本机替换所需的 ${service}。`);
    }
    if (publishedPort(services[service], targetPort) !== expectedPort) {
      throw new BlockedError(
        `${service} 必须将 ${targetPort} 固定发布为回环 ${expectedPort}。`,
      );
    }
  }
  const environment = Object.fromEntries(
    additionalEnvironmentNames(definition).map((name) => [
      name,
      environmentValue(application.environment, name, definition.service),
    ]),
  );
  const secretFiles = Object.fromEntries(
    definition.secretTargets.map((target) => [
      target,
      volumeSource(application, target, definition.service),
    ]),
  );
  return {
    application,
    compose: compose_,
    definition,
    environment,
    nacosPort: publishedPort(nacos, 8848),
    root,
    secretFiles,
    services,
  };
}

async function additionalNacosConfiguration(context) {
  const token = await nacosLogin(
    context,
    context.environment[context.definition.nacosUsername],
    context.environment[context.definition.nacosPassword],
  );
  await nacosRequest(context, token, "/v3/client/cs/config", {
    dataId: context.definition.configDataId,
    groupName: "SAAS_FORGE",
    namespaceId: "dev",
  });
}

async function additionalDiscoveryToken(context) {
  const { definition, environment } = context;
  return nacosLogin(
    context,
    environment[definition.nacosUsername],
    environment[definition.nacosPassword],
  );
}

async function additionalPreflight(context) {
  const entries = await composeEntries(context);
  const { definition } = context;
  if (definition.migration !== undefined) {
    const migration = stateOf(entries, definition.migration);
    if (migration?.State !== "exited" || Number(migration.ExitCode) !== 0) {
      throw new BlockedError(`${definition.migration} 未成功结束。`);
    }
  }
  for (const service of definition.dependencies) {
    const entry = stateOf(entries, service);
    if (!isRunning(entry) || (entry.Health && entry.Health !== "healthy")) {
      throw new BlockedError(
        `${service} 未处于可供本机 ${definition.service} 使用的运行状态。`,
      );
    }
  }
  await assertReadableNonEmpty(...Object.values(context.secretFiles));
  await additionalNacosConfiguration(context);
}

async function localAdditionalProcess(paths, definition) {
  const pid = Number.parseInt(
    await readFile(paths.pid, "utf8").catch(() => ""),
    10,
  );
  if (!Number.isInteger(pid)) return undefined;
  const result = execute("ps", ["-p", String(pid), "-o", "command="], {
    allowFailure: true,
  });
  if (result.status !== 0) return undefined;
  const expected = `/${definition.module}/target/${definition.artifact}-`;
  if (!result.stdout.includes(expected)) {
    throw new BlockedError(
      `${definition.service} PID 文件未指向受管本机进程；拒绝操作未知进程。`,
    );
  }
  return pid;
}

export async function additionalReadiness(definition) {
  // Gateway 的公开路由守卫有意拒绝非 OpenAPI Actuator 路径；其本机探针以监听端口和 Nacos 注册共同判定。
  if (definition.service === "gateway") {
    return localPortAcceptsConnections(definition.httpPort);
  }
  const response = await fetch(
    `http://${localHost}:${definition.httpPort}/actuator/health/readiness`,
    { signal: AbortSignal.timeout(1_000) },
  ).catch(() => undefined);
  return response?.status === 200;
}

async function localPortAcceptsConnections(port) {
  return new Promise((resolve) => {
    const socket = createConnection({ host: localHost, port });
    const finish = (accepting) => {
      socket.destroy();
      resolve(accepting);
    };
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
    socket.setTimeout(1_000, () => finish(false));
  });
}

async function additionalObserve(context, paths, token) {
  const { definition } = context;
  const [entries, pid, instances, hostAddress] = await Promise.all([
    composeEntries(context),
    localAdditionalProcess(paths, definition),
    healthyInstances(context, token, definition.service),
    dockerHostAddress(context),
  ]);
  const localReady =
    pid !== undefined && (await additionalReadiness(definition));
  const localRegistered = instances.some(
    (instance) =>
      instance.ip === hostAddress && instance.port === definition.httpPort,
  );
  const containerRunning = isRunning(stateOf(entries, definition.service));
  return {
    instances,
    state: classifyServiceState({
      containerRunning,
      healthyInstances: instances.length,
      localProcessRunning: pid !== undefined,
      localReady,
      localRegistered,
    }),
  };
}

async function assertAdditionalPortsAvailable(definition) {
  await assertPortAvailable(definition.httpPort);
  if (definition.grpcPort !== undefined)
    await assertPortAvailable(definition.grpcPort);
}

async function packageAdditionalService(context) {
  const { definition } = context;
  execute(
    path.join(context.root, "mvnw"),
    [
      "--batch-mode",
      "--no-transfer-progress",
      "-pl",
      definition.module,
      "-am",
      "package",
      "-DskipTests",
    ],
    { cwd: context.root, env: systemEnvironment() },
  );
  const target = path.join(context.root, definition.module, "target");
  const jar = additionalExecutableJar(
    await readdir(target),
    definition.artifact,
  );
  if (jar === undefined) {
    throw new BlockedError(`无法确定本机 ${definition.service} 可执行 JAR。`);
  }
  return path.join(target, jar);
}

async function startAdditionalService(context, paths, jar, dockerHostAddress) {
  const { definition } = context;
  await mkdir(paths.directory, { recursive: true, mode: 0o700 });
  await chmod(paths.directory, 0o700);
  const log = await open(paths.log, "a", 0o600);
  const child = spawn("java", ["-jar", jar], {
    cwd: context.root,
    detached: true,
    env: {
      ...systemEnvironment(),
      ...localAdditionalEnvironment(definition, context.environment, {
        dockerHostAddress,
        nacosPort: context.nacosPort,
        secretFiles: context.secretFiles,
      }),
    },
    stdio: ["ignore", log.fd, log.fd],
  });
  await log.close();
  if (!child.pid)
    throw new BlockedError(`无法启动本机 ${definition.service} 进程。`);
  child.unref();
  await writeFile(paths.pid, `${child.pid}\n`, { mode: 0o600 });
}

async function stopAdditionalService(paths, definition) {
  const pid = await localAdditionalProcess(paths, definition);
  if (pid === undefined) {
    await rm(paths.pid, { force: true });
    return;
  }
  process.kill(pid, "SIGTERM");
  await waitFor(`本机 ${definition.service} 退出`, async () =>
    processHasExited(pid),
  );
  await rm(paths.pid, { force: true });
}

async function writeGatewayEdgeTarget(paths, hostname) {
  await mkdir(paths.directory, { recursive: true, mode: 0o700 });
  await chmod(paths.directory, 0o700);
  await writeFile(
    paths.apiTarget,
    `${JSON.stringify({ hostname, port: 8080 })}\n`,
    {
      mode: 0o600,
    },
  );
}

async function routeAdditionalTraffic(context, paths) {
  if (context.definition.service === "gateway") {
    await writeGatewayEdgeTarget(paths, dockerHost);
  }
}

async function restoreAdditionalTraffic(context, paths) {
  if (context.definition.service === "gateway") {
    await writeGatewayEdgeTarget(paths, "gateway");
  }
}

async function ensureAdditionalContainer(context, paths, token) {
  const { definition } = context;
  await stopAdditionalService(paths, definition);
  const entries = await composeEntries(context);
  if (!isRunning(stateOf(entries, definition.service))) {
    await waitFor(
      `本机 ${definition.service} 从 Nacos 摘除`,
      async () =>
        (await healthyInstances(context, token, definition.service)).length ===
        0,
    );
  }
  const startArguments = reusableContainerStartArguments(
    stateOf(entries, definition.service),
  );
  if (startArguments !== undefined) {
    execute("docker", startArguments, { cwd: context.compose.directory });
  } else {
    compose(
      context,
      "up",
      "--detach",
      "--no-deps",
      "--force-recreate",
      definition.service,
    );
  }
  await waitFor(
    `容器 ${definition.service} 就绪`,
    async () =>
      (await additionalObserve(context, paths, token)).state === "CONTAINER",
  );
  await restoreAdditionalTraffic(context, paths);
}

async function replaceAdditional(context, paths) {
  const token = await additionalDiscoveryToken(context);
  await additionalPreflight(context);
  const current = await additionalObserve(context, paths, token);
  const { definition } = context;
  if (current.state === "LOCAL") {
    console.log(`REPLACE: ${definition.service} 已处于本机运行，未重复启动。`);
    return;
  }
  if (current.state === "DUPLICATE") {
    throw new BlockedError(
      `Nacos 中存在多个健康 ${definition.service} 实例；拒绝替换。`,
    );
  }
  if (current.state !== "CONTAINER") {
    throw new BlockedError(
      `${definition.service} 未处于可替换的容器状态。请先运行 status。`,
    );
  }
  const jar = await packageAdditionalService(context);
  let containerStopped = false;
  try {
    compose(context, "stop", definition.service);
    containerStopped = true;
    await waitFor(
      `容器 ${definition.service} 停止并从 Nacos 摘除`,
      async () => {
        const entries = await composeEntries(context);
        return (
          !isRunning(stateOf(entries, definition.service)) &&
          (await healthyInstances(context, token, definition.service))
            .length === 0
        );
      },
    );
    await assertAdditionalPortsAvailable(definition);
    await startAdditionalService(
      context,
      paths,
      jar,
      await dockerHostAddress(context),
    );
    await waitFor(
      `本机 ${definition.service} 就绪且完成 Nacos 注册`,
      async () =>
        (await additionalObserve(context, paths, token)).state === "LOCAL",
    );
    await routeAdditionalTraffic(context, paths);
  } catch (error) {
    // 任一失败均恢复容器，避免留下没有路由目标的本地开发栈。
    if (containerStopped) {
      try {
        await ensureAdditionalContainer(context, paths, token);
        console.error(`RECOVERY: 已恢复容器 ${definition.service}。`);
      } catch {
        console.error(
          `RECOVERY: 自动恢复容器 ${definition.service} 失败；请运行 restore ${definition.service}。`,
        );
      }
    }
    throw error;
  }
  console.log(`REPLACE: ${definition.service} 已由本机 JVM 接管。`);
}

async function restoreAdditional(context, paths) {
  const token = await additionalDiscoveryToken(context);
  const current = await additionalObserve(context, paths, token);
  const { definition } = context;
  if (current.state === "DUPLICATE") {
    throw new BlockedError(
      `Nacos 中存在多个健康 ${definition.service} 实例；拒绝在不明确状态下恢复。`,
    );
  }
  if (current.state === "CONTAINER") {
    await stopAdditionalService(paths, definition);
    await restoreAdditionalTraffic(context, paths);
    console.log(`RESTORE: ${definition.service} 已处于容器运行，未重复启动。`);
    return;
  }
  await ensureAdditionalContainer(context, paths, token);
  console.log(`RESTORE: ${definition.service} 已恢复为唯一健康容器实例。`);
}

async function statusAdditional(context, paths) {
  let observed;
  try {
    observed = await additionalObserve(
      context,
      paths,
      await additionalDiscoveryToken(context),
    );
  } catch {
    console.log(
      formatServiceStatus({
        service: context.definition.service,
        state: "UNAVAILABLE",
        httpPort: context.definition.httpPort,
        grpcPort: context.definition.grpcPort,
        healthyInstances: "UNAVAILABLE",
      }),
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    formatServiceStatus({
      service: context.definition.service,
      state: observed.state,
      httpPort: context.definition.httpPort,
      grpcPort: context.definition.grpcPort,
      healthyInstances: observed.instances.length,
    }),
  );
  if (observed.state === "UNAVAILABLE" || observed.state === "DUPLICATE") {
    process.exitCode = 1;
  }
}

async function diagnostic(code, service, recovery, check, success) {
  try {
    await check();
    console.log(`OK [${diagnosticSuccessCode(code)}]: ${service} ${success}`);
    return true;
  } catch {
    console.log(`BLOCKED [${code}]: ${service} 前置条件未满足。`);
    console.log(`恢复：${recovery}`);
    return false;
  }
}

export function diagnosticSuccessCode(failureCode) {
  return failureCode.replace(/_(?:FAILED|INVALID|MISSING|UNAVAILABLE)$/u, "");
}

function migrationIsSuccessful(entries, migration) {
  const entry = stateOf(entries, migration);
  if (entry?.State !== "exited" || Number(entry.ExitCode) !== 0) {
    throw new BlockedError(`${migration} 未成功结束。`);
  }
}

function infrastructureIsHealthy(entries, services, selectedService) {
  for (const service of services) {
    if (service === selectedService) continue;
    const entry = stateOf(entries, service);
    if (!isRunning(entry) || (entry.Health && entry.Health !== "healthy")) {
      throw new BlockedError(`${service} 未处于健康运行状态。`);
    }
  }
}

async function diagnoseTopology(observeCurrent, ports) {
  const observed = await observeCurrent();
  if (observed.state === "DUPLICATE") {
    return { code: "DUPLICATE_INSTANCE", observed };
  }
  if (observed.state === "UNAVAILABLE") {
    try {
      for (const port of ports) await assertPortAvailable(port);
    } catch {
      return { code: "PORT_CONFLICT", observed };
    }
    return { code: "SERVICE_UNAVAILABLE", observed };
  }
  return { code: "TOPOLOGY", observed };
}

async function doctorIam(context, paths) {
  const entries = await composeEntries(context);
  let failures = 0;
  if (
    !(await diagnostic(
      "MIGRATION_FAILED",
      supportedService,
      "修复 iam-migrate 后重新运行 bash scripts/local-development.sh doctor。",
      () => migrationIsSuccessful(entries, "iam-migrate"),
      "迁移已成功完成。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "INFRASTRUCTURE_UNAVAILABLE",
      supportedService,
      "在 deploy/compose 中启动并恢复 PostgreSQL、Redis、Kafka、Mailpit 与 Nacos。",
      () =>
        infrastructureIsHealthy(entries, [
          "postgres",
          "redis",
          "kafka",
          "mailpit",
          "nacos",
        ]),
      "基础设施健康。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "SECRET_MISSING",
      supportedService,
      "按 deploy/compose/README.md 准备 IAM 受限 Secret 文件。",
      () =>
        assertReadableNonEmpty(
          context.signingKeyFile,
          context.serviceClientIdFile,
          context.serviceClientSecretFile,
        ),
      "所需 Secret 文件可读且非空。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "SIGNING_KEY_INVALID",
      supportedService,
      "运行 bash scripts/initialize-local-iam-signing-key.sh，并保留现有数据卷。",
      () => verifySigningKey(context),
      "ACTIVE Signing Key 与本地私钥匹配。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "NACOS_UNAVAILABLE",
      supportedService,
      "恢复 Nacos 与 iam-service.yaml 后重新运行 doctor。",
      () => assertNacosConfiguration(context),
      "Nacos 配置可读取。",
    ))
  )
    failures += 1;
  try {
    const topology = await diagnoseTopology(
      async () => observe(context, paths, await gatewayDiscoveryToken(context)),
      [localHttpPort, localGrpcPort],
    );
    if (topology.code === "TOPOLOGY") {
      console.log(
        `OK [TOPOLOGY]: ${supportedService} ${topology.observed.state}，Nacos 健康实例数 ${topology.observed.instances.length}。`,
      );
    } else {
      console.log(
        `BLOCKED [${topology.code}]: ${supportedService} ${topology.observed.state}，Nacos 健康实例数 ${topology.observed.instances.length}。`,
      );
      console.log(
        `恢复：运行 bash scripts/local-development.sh restore ${supportedService}；若端口冲突，先停止未知监听者。`,
      );
      failures += 1;
    }
  } catch {
    console.log(
      `BLOCKED [TOPOLOGY_UNREADABLE]: ${supportedService} 状态无法确认。`,
    );
    console.log(
      "恢复：恢复 Docker 与 Nacos 后重新运行 doctor；若仍失败，再检查固定端口监听者。",
    );
    failures += 1;
  }
  return failures;
}

async function doctorAdditional(context, paths) {
  const { definition } = context;
  const entries = await composeEntries(context);
  let failures = 0;
  if (
    definition.migration !== undefined &&
    !(await diagnostic(
      "MIGRATION_FAILED",
      definition.service,
      `修复 ${definition.migration} 后重新运行 bash scripts/local-development.sh doctor。`,
      () => migrationIsSuccessful(entries, definition.migration),
      "迁移已成功完成。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "INFRASTRUCTURE_UNAVAILABLE",
      definition.service,
      "启动该目标依赖的 Compose 基础设施与应用服务后重新运行 doctor。",
      () =>
        infrastructureIsHealthy(
          entries,
          definition.dependencies,
          definition.service,
        ),
      "依赖服务健康。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "SECRET_MISSING",
      definition.service,
      "按 deploy/compose/README.md 准备该目标的受限 Secret 文件。",
      () => assertReadableNonEmpty(...Object.values(context.secretFiles)),
      "所需 Secret 文件可读且非空。",
    ))
  )
    failures += 1;
  if (
    !(await diagnostic(
      "NACOS_UNAVAILABLE",
      definition.service,
      `恢复 Nacos 与 ${definition.configDataId} 后重新运行 doctor。`,
      () => additionalNacosConfiguration(context),
      "Nacos 配置可读取。",
    ))
  )
    failures += 1;
  try {
    const topology = await diagnoseTopology(
      async () =>
        additionalObserve(
          context,
          paths,
          await additionalDiscoveryToken(context),
        ),
      [
        definition.httpPort,
        ...(definition.grpcPort === undefined ? [] : [definition.grpcPort]),
      ],
    );
    if (topology.code === "TOPOLOGY") {
      console.log(
        `OK [TOPOLOGY]: ${definition.service} ${topology.observed.state}，Nacos 健康实例数 ${topology.observed.instances.length}。`,
      );
    } else {
      console.log(
        `BLOCKED [${topology.code}]: ${definition.service} ${topology.observed.state}，Nacos 健康实例数 ${topology.observed.instances.length}。`,
      );
      console.log(
        `恢复：运行 bash scripts/local-development.sh restore ${definition.service}；若端口冲突，先停止未知监听者。`,
      );
      failures += 1;
    }
  } catch {
    console.log(
      `BLOCKED [TOPOLOGY_UNREADABLE]: ${definition.service} 状态无法确认。`,
    );
    console.log(
      "恢复：恢复 Docker 与 Nacos 后重新运行 doctor；若仍失败，再检查该目标固定端口监听者。",
    );
    failures += 1;
  }
  return failures;
}

async function doctorTarget(root, service) {
  try {
    if (service === supportedService) {
      const context = loadContext(root);
      console.log(
        `OK [PORTS]: ${service} http=${localHttpPort} grpc=${localGrpcPort} nacos-http=${context.nacosPort} nacos-grpc=${context.nacosGrpcPort}。`,
      );
      return await doctorIam(context, runtimePaths(root));
    }
    const definition = additionalServiceDefinition(service);
    const context = additionalContext(root, definition);
    console.log(
      `OK [PORTS]: ${service} http=${definition.httpPort}${definition.grpcPort === undefined ? "" : ` grpc=${definition.grpcPort}`} nacos-http=${context.nacosPort}。`,
    );
    return await doctorAdditional(
      context,
      additionalRuntimePaths(root, definition),
    );
  } catch {
    console.log(
      `BLOCKED [COMPOSE_CONFIG_INVALID]: ${service} 无法加载固定端口、环境或 Secret 挂载配置。`,
    );
    console.log(
      "恢复：修复 deploy/compose/.env 与 Compose 配置后重新运行 doctor；不要输出环境变量值。",
    );
    return 1;
  }
}

function usage() {
  console.error(
    "用法：bash scripts/local-service-replacement.sh <doctor|replace|status|restore> <gateway|iam-service|tenant-access-service|entitlement-service|audit-service>",
  );
}

async function main(arguments_) {
  const [command, service] = arguments_;
  if (
    !["doctor", "replace", "status", "restore"].includes(command) ||
    arguments_.length !== 2
  ) {
    usage();
    process.exitCode = 2;
    return;
  }
  try {
    assertSupportedService(service);
    const root = rootDirectory();
    if (command === "doctor") {
      if ((await doctorTarget(root, service)) > 0) process.exitCode = 1;
      return;
    }
    if (service === supportedService) {
      const context = loadContext(root);
      const paths = runtimePaths(root);
      if (command === "replace") await replace(context, paths);
      if (command === "status") await status(context, paths);
      if (command === "restore") await restore(context, paths);
    } else {
      const definition = additionalServiceDefinition(service);
      const context = additionalContext(root, definition);
      const paths = additionalRuntimePaths(root, definition);
      if (command === "replace") await replaceAdditional(context, paths);
      if (command === "status") await statusAdditional(context, paths);
      if (command === "restore") await restoreAdditional(context, paths);
    }
  } catch (error) {
    if (command === "status") {
      console.log(unavailableServiceStatus(service));
      process.exitCode = 1;
      return;
    }
    console.error(
      `BLOCKED: ${error instanceof Error ? error.message : "本地服务替换失败。"}`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
