import { spawn, spawnSync } from "node:child_process";
import { constants } from "node:fs";
import {
  access,
  chmod,
  mkdir,
  open,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { createInterface } from "node:readline/promises";
import { fileURLToPath } from "node:url";

const expectedNodeVersion = "24.14.1";
const expectedPnpmVersion = "11.22.0";
const certificateValiditySeconds = 24 * 60 * 60;
const certificateAuthorityName = "SaaS Forge Local Development CA";
const hostsEntry =
  "127.0.0.1 platform.saasforge.test api.saasforge.test # SaaS Forge local HTTPS";

export const developmentHosts = Object.freeze([
  "platform.saasforge.test",
  "api.saasforge.test",
]);

export function developmentHttpsPaths(repositoryRoot) {
  const directory = path.join(
    repositoryRoot,
    "deploy",
    "compose",
    ".secrets",
    "local-https-development",
  );
  return {
    directory,
    certificateAuthorityKey: path.join(directory, "root-ca.key"),
    certificateAuthorityCertificate: path.join(directory, "root-ca.pem"),
    serverKey: path.join(directory, "server.key"),
    serverCertificate: path.join(directory, "server.pem"),
    serverRequest: path.join(directory, "server.csr"),
    serverExtensions: path.join(directory, "server.ext"),
    apiTarget: path.join(
      repositoryRoot,
      "deploy",
      "compose",
      ".secrets",
      "local-service-replacement",
      "api-target.json",
    ),
    viteLog: path.join(directory, "vite.log"),
    vitePid: path.join(directory, "vite.pid"),
  };
}

export async function certificateCoversExpectedHosts(certificate) {
  const result = run(
    "openssl",
    ["x509", "-in", certificate, "-noout", "-ext", "subjectAltName"],
    {
      allowFailure: true,
    },
  );
  return (
    result.status === 0 &&
    developmentHosts.every((host) => result.stdout.includes(`DNS:${host}`))
  );
}

export async function ensureCertificateMaterial(paths) {
  await mkdir(paths.directory, { recursive: true, mode: 0o700 });
  await chmod(paths.directory, 0o700);

  const authorityIsUsable = await certificateAuthorityIsUsable(paths);
  const serverIsUsable =
    authorityIsUsable && (await serverCertificateIsUsable(paths));
  if (serverIsUsable) {
    return "reused";
  }

  if (!authorityIsUsable) {
    await removeCertificateAuthority(paths);
    run("openssl", [
      "req",
      "-x509",
      "-new",
      "-nodes",
      "-newkey",
      "rsa:3072",
      "-sha256",
      "-days",
      "3650",
      "-keyout",
      paths.certificateAuthorityKey,
      "-out",
      paths.certificateAuthorityCertificate,
      "-subj",
      `/CN=${certificateAuthorityName}`,
      "-addext",
      "basicConstraints=critical,CA:TRUE,pathlen:0",
      "-addext",
      "keyUsage=critical,keyCertSign,cRLSign",
      "-addext",
      "subjectKeyIdentifier=hash",
    ]);
    await chmod(paths.certificateAuthorityKey, 0o600);
    await chmod(paths.certificateAuthorityCertificate, 0o644);
  }

  await removeServerCertificate(paths);
  await writeFile(
    paths.serverExtensions,
    [
      "basicConstraints=critical,CA:FALSE",
      "keyUsage=critical,digitalSignature,keyEncipherment",
      "extendedKeyUsage=serverAuth",
      `subjectAltName=${developmentHosts.map((host) => `DNS:${host}`).join(",")}`,
      "subjectKeyIdentifier=hash",
      "authorityKeyIdentifier=keyid,issuer",
      "",
    ].join("\n"),
    { mode: 0o600 },
  );
  run("openssl", [
    "req",
    "-new",
    "-nodes",
    "-newkey",
    "rsa:3072",
    "-sha256",
    "-keyout",
    paths.serverKey,
    "-out",
    paths.serverRequest,
    "-subj",
    "/CN=platform.saasforge.test",
  ]);
  run("openssl", [
    "x509",
    "-req",
    "-in",
    paths.serverRequest,
    "-CA",
    paths.certificateAuthorityCertificate,
    "-CAkey",
    paths.certificateAuthorityKey,
    "-CAcreateserial",
    "-days",
    "397",
    "-sha256",
    "-extfile",
    paths.serverExtensions,
    "-out",
    paths.serverCertificate,
  ]);
  await chmod(paths.serverKey, 0o640);
  await chmod(paths.serverCertificate, 0o644);
  return "created";
}

export function hasExpectedHosts(content) {
  const configuredHosts = new Set();
  for (const line of content.split(/\r?\n/u)) {
    const fields = line.replace(/#.*/u, "").trim().split(/\s+/u);
    if (fields[0] !== "127.0.0.1") continue;
    for (const host of fields.slice(1)) configuredHosts.add(host);
  }
  return developmentHosts.every((host) => configuredHosts.has(host));
}

export function viteDevelopmentCommand(consoleRoot) {
  return {
    command: "mise",
    args: [
      "exec",
      `node@${expectedNodeVersion}`,
      "--",
      "corepack",
      "pnpm",
      "--filter",
      "@saas-forge/platform-console",
      "run",
      "dev",
      "--",
      "--host",
      "0.0.0.0",
      "--port",
      "5173",
    ],
    cwd: consoleRoot,
  };
}

async function certificateAuthorityIsUsable(paths) {
  if (
    !(await filesExist(
      paths.certificateAuthorityKey,
      paths.certificateAuthorityCertificate,
    ))
  )
    return false;
  return certificateHasRemainingValidity(paths.certificateAuthorityCertificate);
}

async function serverCertificateIsUsable(paths) {
  if (!(await filesExist(paths.serverKey, paths.serverCertificate)))
    return false;
  if (!(await certificateHasRemainingValidity(paths.serverCertificate)))
    return false;
  if (!(await certificateCoversExpectedHosts(paths.serverCertificate)))
    return false;
  if (
    run(
      "openssl",
      [
        "verify",
        "-CAfile",
        paths.certificateAuthorityCertificate,
        paths.serverCertificate,
      ],
      { allowFailure: true },
    ).status !== 0
  ) {
    return false;
  }
  const certificateModulus = run(
    "openssl",
    ["x509", "-noout", "-modulus", "-in", paths.serverCertificate],
    {
      allowFailure: true,
    },
  );
  const keyModulus = run(
    "openssl",
    ["rsa", "-noout", "-modulus", "-in", paths.serverKey],
    {
      allowFailure: true,
    },
  );
  return (
    certificateModulus.status === 0 &&
    keyModulus.status === 0 &&
    certificateModulus.stdout === keyModulus.stdout
  );
}

async function certificateHasRemainingValidity(certificate) {
  return (
    run(
      "openssl",
      [
        "x509",
        "-checkend",
        String(certificateValiditySeconds),
        "-noout",
        "-in",
        certificate,
      ],
      {
        allowFailure: true,
      },
    ).status === 0
  );
}

async function filesExist(...files) {
  for (const file of files) {
    try {
      await access(file, constants.R_OK);
    } catch {
      return false;
    }
  }
  return true;
}

async function removeCertificateAuthority(paths) {
  await Promise.all([
    rm(paths.certificateAuthorityKey, { force: true }),
    rm(paths.certificateAuthorityCertificate, { force: true }),
  ]);
}

async function removeServerCertificate(paths) {
  await Promise.all([
    rm(paths.serverKey, { force: true }),
    rm(paths.serverCertificate, { force: true }),
    rm(paths.serverRequest, { force: true }),
    rm(paths.serverExtensions, { force: true }),
    rm(path.join(paths.directory, "root-ca.srl"), { force: true }),
  ]);
}

function run(command, args, { allowFailure = false, cwd, env, input } = {}) {
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: "utf8",
    input,
    stdio: ["pipe", "pipe", "pipe"],
  });
  if (result.error && !allowFailure) {
    throw new Error(`无法执行 ${command}；请安装或修复该工具后重试。`);
  }
  if (result.status !== 0 && !allowFailure) {
    throw new Error(`${command} 执行失败；请运行 doctor 查看恢复提示。`);
  }
  return {
    status: result.status ?? 1,
    stdout: result.stdout ?? "",
  };
}

async function installHosts() {
  const existing = await readFile("/etc/hosts", "utf8");
  if (hasExpectedHosts(existing)) {
    console.log("HOSTS: 已配置，未修改 /etc/hosts。");
    return;
  }
  await confirm(
    "此操作将向 /etc/hosts 添加两个仅指向 127.0.0.1 的本地域名。输入 HOSTS 以明确授权： ",
    "HOSTS",
  );
  run("sudo", ["tee", "-a", "/etc/hosts"], { input: `${hostsEntry}\n` });
  console.log("HOSTS: 已添加本地域名。");
}

async function trustCertificateAuthority(paths) {
  if (process.platform !== "darwin") {
    throw new Error("本地 HTTPS 开发入口仅支持 macOS Docker Desktop。");
  }
  if (!(await filesExist(paths.certificateAuthorityCertificate))) {
    throw new Error("缺少本地 CA；请先运行 setup。");
  }
  if (isCertificateAuthorityTrusted()) {
    console.log("TRUST: 本地 CA 已在系统信任库中，未重复安装。");
    return;
  }
  await confirm(
    "此操作将把 SaaS Forge 本地开发 CA 加入 macOS System Keychain 并设为信任根。输入 TRUST_CA 以明确授权： ",
    "TRUST_CA",
  );
  run("sudo", [
    "security",
    "add-trusted-cert",
    "-d",
    "-r",
    "trustRoot",
    "-k",
    "/Library/Keychains/System.keychain",
    paths.certificateAuthorityCertificate,
  ]);
  console.log("TRUST: 本地 CA 已加入 macOS System Keychain。");
}

function isCertificateAuthorityTrusted() {
  return (
    run(
      "security",
      [
        "find-certificate",
        "-c",
        certificateAuthorityName,
        "/Library/Keychains/System.keychain",
      ],
      {
        allowFailure: true,
      },
    ).status === 0
  );
}

async function confirm(question, expectedValue) {
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    throw new Error("拒绝在非交互终端修改系统设置。请在终端中重新执行此命令。");
  }
  const readline = createInterface({
    input: process.stdin,
    output: process.stdout,
  });
  try {
    const value = await readline.question(question);
    if (value !== expectedValue) {
      throw new Error("未收到明确授权，未修改系统设置。");
    }
  } finally {
    readline.close();
  }
}

async function doctor(repositoryRoot, paths) {
  const results = [];
  results.push(await doctorCertificate(paths));
  results.push(await doctorHosts());
  results.push(doctorTrust());
  results.push(doctorPort(paths));
  results.push(doctorDocker());
  results.push(doctorToolchain(repositoryRoot));

  let failures = 0;
  for (const result of results) {
    console.log(
      `${result.ok ? "OK" : "BLOCKED"} [${result.code}]: ${result.message}`,
    );
    if (!result.ok) {
      failures += 1;
      console.log(`恢复：${result.recovery}`);
    }
  }
  if (failures > 0) process.exitCode = 1;
}

async function doctorCertificate(paths) {
  if (
    !(await filesExist(
      paths.certificateAuthorityKey,
      paths.certificateAuthorityCertificate,
      paths.serverKey,
      paths.serverCertificate,
    ))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_MISSING",
      message: "本地 CA 或服务器证书文件缺失。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (
    !(await certificateHasRemainingValidity(
      paths.certificateAuthorityCertificate,
    )) ||
    !(await certificateHasRemainingValidity(paths.serverCertificate))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_EXPIRED",
      message: "本地 CA 或服务器证书已过期或将在 24 小时内过期。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (!(await certificateCoversExpectedHosts(paths.serverCertificate))) {
    return {
      ok: false,
      code: "CERTIFICATE_HOST_MISMATCH",
      message: "服务器证书未覆盖固定 Platform/API Host。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  if (
    !(await certificateAuthorityIsUsable(paths)) ||
    !(await serverCertificateIsUsable(paths))
  ) {
    return {
      ok: false,
      code: "CERTIFICATE_INVALID",
      message: "本地证书链或私钥匹配校验失败。",
      recovery: "bash scripts/local-development.sh setup",
    };
  }
  return {
    ok: true,
    code: "CERTIFICATE",
    message: "本地 CA 和服务器证书有效。",
  };
}

async function doctorHosts() {
  const hosts = await readFile("/etc/hosts", "utf8").catch(() => "");
  if (!hasExpectedHosts(hosts)) {
    return {
      ok: false,
      code: "HOSTS_MISSING",
      message:
        "platform.saasforge.test 或 api.saasforge.test 未在 /etc/hosts 指向 127.0.0.1。",
      recovery: "bash scripts/local-https-development.sh hosts",
    };
  }
  return {
    ok: true,
    code: "HOSTS",
    message: "两个本地域名均由 /etc/hosts 指向 127.0.0.1。",
  };
}

function doctorTrust() {
  if (process.platform !== "darwin") {
    return {
      ok: false,
      code: "PLATFORM_UNSUPPORTED",
      message: "当前系统不是受支持的 macOS Docker Desktop 环境。",
      recovery: "请在 macOS Docker Desktop 中运行该入口。",
    };
  }
  if (!isCertificateAuthorityTrusted()) {
    return {
      ok: false,
      code: "CERTIFICATE_UNTRUSTED",
      message: "本地 CA 未安装到 macOS System Keychain。",
      recovery: "bash scripts/local-https-development.sh trust-ca",
    };
  }
  return {
    ok: true,
    code: "CERTIFICATE_TRUST",
    message: "本地 CA 已安装到 macOS System Keychain。",
  };
}

function doctorPort(paths) {
  const result = run("lsof", ["-nP", "-iTCP:443", "-sTCP:LISTEN"], {
    allowFailure: true,
  });
  if (result.status === 0) {
    const edge = run(
      "curl",
      [
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout",
        "2",
        "--max-time",
        "5",
        "--cacert",
        paths.certificateAuthorityCertificate,
        "https://platform.saasforge.test/",
      ],
      { allowFailure: true },
    );
    if (edge.status === 0) {
      return {
        ok: true,
        code: "HTTPS_PORT",
        message: "443 端口由可验证的本地 HTTPS Edge 提供。",
      };
    }
    return {
      ok: false,
      code: "PORT_CONFLICT",
      message: "127.0.0.1:443 已有监听者。",
      recovery:
        "停止占用 443 的本地服务后重试；不要同时启动验收 console-tls 入口。",
    };
  }
  return {
    ok: true,
    code: "HTTPS_PORT",
    message: "443 端口可供 Docker TLS Edge 使用。",
  };
}

function doctorDocker() {
  const result = run("docker", ["info", "--format", "{{.ServerVersion}}"], {
    allowFailure: true,
  });
  if (result.status !== 0) {
    return {
      ok: false,
      code: "DOCKER_UNAVAILABLE",
      message: "Docker Desktop 不可用。",
      recovery: "启动 Docker Desktop，然后重新运行 doctor。",
    };
  }
  return { ok: true, code: "DOCKER", message: "Docker Desktop 可用。" };
}

function doctorToolchain(repositoryRoot) {
  const consoleRoot = path.join(repositoryRoot, "consoles");
  const command = viteDevelopmentCommand(consoleRoot);
  const environment = { ...process.env, COREPACK_ENABLE_NETWORK: "0" };
  const node = run(
    "mise",
    ["exec", `node@${expectedNodeVersion}`, "--", "node", "--version"],
    {
      allowFailure: true,
      cwd: consoleRoot,
      env: environment,
    },
  );
  const pnpm = run(
    "mise",
    [
      "exec",
      `node@${expectedNodeVersion}`,
      "--",
      "corepack",
      "pnpm",
      "--version",
    ],
    {
      allowFailure: true,
      cwd: command.cwd,
      env: environment,
    },
  );
  const dependencies = run(
    "test",
    ["-d", path.join(consoleRoot, "node_modules")],
    { allowFailure: true },
  );
  if (
    node.status !== 0 ||
    node.stdout.trim() !== `v${expectedNodeVersion}` ||
    pnpm.status !== 0 ||
    pnpm.stdout.trim() !== expectedPnpmVersion ||
    dependencies.status !== 0
  ) {
    return {
      ok: false,
      code: "TOOLCHAIN_INVALID",
      message: `Platform Console 需要 Node ${expectedNodeVersion}、pnpm ${expectedPnpmVersion} 和既有依赖。`,
      recovery:
        "在 consoles/ 中显式运行 pnpm install --frozen-lockfile；doctor 与 start 不会安装依赖。",
    };
  }
  return {
    ok: true,
    code: "TOOLCHAIN",
    message: "Platform Console Node、pnpm 和依赖目录均符合固定配置。",
  };
}

async function start(repositoryRoot, paths) {
  if (
    !(await certificateAuthorityIsUsable(paths)) ||
    !(await serverCertificateIsUsable(paths))
  ) {
    throw new Error("本地证书尚未就绪；请先运行 setup。");
  }
  if (!hasExpectedHosts(await readFile("/etc/hosts", "utf8"))) {
    throw new Error("本地域名尚未就绪；请先运行 hosts。");
  }
  await ensureApiTarget(paths.apiTarget);
  const toolchain = doctorToolchain(repositoryRoot);
  if (!toolchain.ok)
    throw new Error(`${toolchain.message} ${toolchain.recovery}`);

  await startVite(repositoryRoot, paths);
  await waitForVite();
  startEdge(repositoryRoot, paths);
  console.log("READY: https://platform.saasforge.test");
}

async function ensureApiTarget(targetFile) {
  try {
    await access(targetFile, constants.R_OK);
  } catch {
    await mkdir(path.dirname(targetFile), { recursive: true, mode: 0o700 });
    await chmod(path.dirname(targetFile), 0o700);
    await writeFile(targetFile, '{"hostname":"gateway","port":8080}\n', {
      mode: 0o600,
    });
  }
}

async function startVite(repositoryRoot, paths) {
  const existingPid = Number.parseInt(
    await readFile(paths.vitePid, "utf8").catch(() => ""),
    10,
  );
  if (Number.isInteger(existingPid) && processIsAlive(existingPid)) {
    console.log("VITE: 已在运行，未重复启动。");
    return;
  }
  await rm(paths.vitePid, { force: true });
  const command = viteDevelopmentCommand(path.join(repositoryRoot, "consoles"));
  const log = await open(paths.viteLog, "a", 0o600);
  const child = spawn(command.command, command.args, {
    cwd: command.cwd,
    detached: true,
    env: {
      ...process.env,
      COREPACK_ENABLE_NETWORK: "0",
      PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE: "false",
    },
    stdio: ["ignore", log.fd, log.fd],
  });
  child.unref();
  await writeFile(paths.vitePid, `${child.pid}\n`, { mode: 0o600 });
  await log.close();
  console.log("VITE: 已以固定端口 5173 启动。");
}

function processIsAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitForVite() {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const response = await fetch("http://127.0.0.1:5173/", {
      signal: AbortSignal.timeout(1_000),
    }).catch(() => undefined);
    if (response?.ok) return;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(
    "Vite 未在 30 秒内就绪；请查看 deploy/compose/.secrets/local-https-development/vite.log。",
  );
}

export function edgeStartArguments(repositoryRoot) {
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  return [
    "compose",
    "--project-directory",
    composeDirectory,
    "--file",
    path.join(composeDirectory, "compose.yaml"),
    "--file",
    path.join(composeDirectory, "local-https-development.override.yaml"),
    "up",
    "--detach",
    // Edge 是独立入口；启动依赖会重建应用拓扑，超出该恢复命令的边界。
    "--no-deps",
    "--force-recreate",
    "local-https-edge",
  ];
}

function startEdge(repositoryRoot, paths) {
  const composeDirectory = path.join(repositoryRoot, "deploy", "compose");
  run("docker", edgeStartArguments(repositoryRoot), {
    cwd: composeDirectory,
    env: {
      ...process.env,
      SF_LOCAL_HTTPS_CERT: paths.serverCertificate,
      SF_LOCAL_HTTPS_API_TARGET_FILE: paths.apiTarget,
      SF_LOCAL_HTTPS_KEY: paths.serverKey,
      SF_LOCAL_HTTPS_HOST_GID: String(process.getgid?.() ?? os.userInfo().gid),
    },
  });
}

function usage() {
  console.error(
    "用法：bash scripts/local-https-development.sh <setup|hosts|trust-ca|doctor|start>",
  );
}

async function main(arguments_) {
  const [command] = arguments_;
  if (
    !["setup", "hosts", "trust-ca", "doctor", "start"].includes(command) ||
    arguments_.length !== 1
  ) {
    usage();
    process.exitCode = 2;
    return;
  }
  const repositoryRoot = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
  );
  const paths = developmentHttpsPaths(repositoryRoot);
  try {
    switch (command) {
      case "setup": {
        const result = await ensureCertificateMaterial(paths);
        console.log(
          `CERTIFICATE: 已${result === "created" ? "创建" : "复用"}本地 CA 与服务器证书。`,
        );
        console.log(
          "NEXT: 运行 hosts 和 trust-ca；两者都会在修改系统前请求明确授权。",
        );
        break;
      }
      case "hosts":
        await installHosts();
        break;
      case "trust-ca":
        await trustCertificateAuthority(paths);
        break;
      case "doctor":
        await doctor(repositoryRoot, paths);
        break;
      case "start":
        await start(repositoryRoot, paths);
        break;
      default:
        throw new Error("不支持的命令。");
    }
  } catch (error) {
    console.error(
      `BLOCKED: ${error instanceof Error ? error.message : "本地 HTTPS 开发入口失败。"}`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
