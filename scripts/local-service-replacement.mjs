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
import { isIP } from "node:net";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const supportedService = "iam-service";
const localHttpPort = 8081;
const localHost = "127.0.0.1";
const timeoutMilliseconds = 90_000;

class BlockedError extends Error {}

export function assertSupportedService(service) {
  if (service !== supportedService) {
    throw new BlockedError(
      "此版本只支持 iam-service；服务目标必须显式传入且不能使用别名。",
    );
  }
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

function environmentValue(environment, name) {
  const value = Array.isArray(environment)
    ? environment
        .find((candidate) => candidate.startsWith(`${name}=`))
        ?.slice(name.length + 1)
    : environment?.[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new BlockedError(`iam-service 缺少必需环境变量 ${name}。`);
  }
  return value;
}

function volumeSource(service, target) {
  const volume = service.volumes?.find(
    (candidate) => typeof candidate === "object" && candidate.target === target,
  );
  if (typeof volume?.source !== "string" || volume.source.length === 0) {
    throw new BlockedError(`iam-service 缺少受限 Secret 挂载 ${target}。`);
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
  if (!iam || !gateway || !nacos) {
    throw new BlockedError("Compose 配置缺少 iam-service、gateway 或 nacos。");
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

function stateOf(entries, service) {
  return entries.find((entry) => entry.Service === service);
}

function isRunning(entry) {
  return entry?.State === "running";
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
  // IAM 身份只有注册权限；生命周期状态只能使用既有 Gateway 发现身份做只读查询。
  return nacosLogin(
    context,
    context.gatewayDiscovery.username,
    context.gatewayDiscovery.password,
  );
}

async function healthyInstances(context, token) {
  const payload = await nacosRequest(
    context,
    token,
    "/v3/admin/ns/instance/list",
    {
      groupName: "DEFAULT_GROUP",
      healthyOnly: "true",
      namespaceId: "dev",
      serviceName: supportedService,
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
  await waitFor(
    "本机 IAM 退出",
    async () => (await localProcess(paths)) === undefined,
  );
  await rm(paths.iamPid, { force: true });
}

async function ensureContainer(context, paths, token) {
  await stopLocalIam(paths);
  await waitFor(
    "本机 IAM 从 Nacos 摘除",
    async () => (await healthyInstances(context, token)).length === 0,
  );
  compose(context, "start", supportedService);
  await waitFor(
    "容器 IAM 就绪",
    async () => (await observe(context, paths, token)).state === "CONTAINER",
  );
}

async function replace(context, paths) {
  await preflight(context);
  const token = await gatewayDiscoveryToken(context);
  const current = await observe(context, paths, token);
  if (current.state === "LOCAL") {
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
    await startLocalIam(context, paths, jar, await dockerHostAddress(context));
    await waitFor(
      "本机 IAM 就绪且完成 Nacos 注册",
      async () => (await observe(context, paths, token)).state === "LOCAL",
    );
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
    console.log("RESTORE: iam-service 已处于容器运行，未重复启动。");
    return;
  }
  await ensureContainer(context, paths, token);
  console.log("RESTORE: iam-service 已恢复为唯一健康容器实例。");
}

async function status(context, paths) {
  const observed = await observe(
    context,
    paths,
    await gatewayDiscoveryToken(context),
  );
  console.log(`STATUS: iam-service ${observed.state}`);
  if (observed.state === "UNAVAILABLE" || observed.state === "DUPLICATE") {
    process.exitCode = 1;
  }
}

function usage() {
  console.error(
    "用法：bash scripts/local-service-replacement.sh <replace|status|restore> iam-service",
  );
}

async function main(arguments_) {
  const [command, service] = arguments_;
  if (
    !["replace", "status", "restore"].includes(command) ||
    arguments_.length !== 2
  ) {
    usage();
    process.exitCode = 2;
    return;
  }
  try {
    assertSupportedService(service);
    const root = rootDirectory();
    const context = loadContext(root);
    const paths = runtimePaths(root);
    if (command === "replace") await replace(context, paths);
    if (command === "status") await status(context, paths);
    if (command === "restore") await restore(context, paths);
  } catch (error) {
    console.error(
      `BLOCKED: ${error instanceof Error ? error.message : "本地服务替换失败。"}`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
