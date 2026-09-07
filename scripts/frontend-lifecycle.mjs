export const frontendTargets = Object.freeze({
  platform: Object.freeze({
    label: "Platform",
    package: "@saas-forge/platform-console",
    port: 5173,
    host: "platform.saasforge.test",
  }),
  tenant: Object.freeze({
    label: "Tenant",
    package: "@saas-forge/tenant-console-shell",
    port: 5174,
    host: "console.saasforge.test",
  }),
});

export function frontendTarget(name) {
  if (!Object.hasOwn(frontendTargets, name))
    throw new Error(`不支持的 Console 目标：${name}`);
  return frontendTargets[name];
}
const startupWindowMilliseconds = 30_000;

/**
 * Console 生命周期的公开 interface。调用方只选择固定目标与操作；状态优先级、
 * PID 身份、幂等和失败回滚都封装在 module 内，系统副作用由 Adapter 提供。
 */
export function createFrontendLifecycle({ repositoryRoot, system, target }) {
  const configuration = frontendTarget(target);
  const { label, port, host } = configuration;
  const consoleRoot = `${repositoryRoot}/consoles`;

  async function observe() {
    const [pidFile, legacyPidFile, listener] = await Promise.all([
      system.readPidFile(),
      target === "platform" ? system.readLegacyPidFile?.() : undefined,
      system.inspectListener(port),
    ]);
    if (pidFile === undefined && legacyPidFile === undefined) {
      return listener === undefined ? "STOPPED" : "UNMANAGED";
    }

    let record = parseManagedPid(pidFile);
    const legacyPid =
      pidFile === undefined ? parseLegacyPid(legacyPidFile) : undefined;
    if (record === undefined && legacyPid !== undefined) {
      const legacyProcess = await system.inspectProcess(legacyPid);
      if (legacyProcess === undefined) {
        return listener === undefined ? "STALE" : "UNMANAGED";
      }
      if (!isLegacyPlatformProcess(legacyProcess, consoleRoot, legacyPid)) {
        return "UNMANAGED";
      }
      record = {
        version: 1,
        pid: legacyPid,
        startedAt: 0,
        processStartedAt: legacyProcess.startedAt,
      };
    }
    if (record === undefined) return "UNMANAGED";
    const process = await system.inspectProcess(record.pid);
    if (process === undefined) {
      return listener === undefined ? "STALE" : "UNMANAGED";
    }
    if (!isConsoleProcess(process, consoleRoot, record, configuration)) {
      return "UNMANAGED";
    }
    if (listener === undefined) {
      return system.now() - record.startedAt < startupWindowMilliseconds
        ? "STARTING"
        : "UNREADY";
    }
    if (
      listener.address !== "127.0.0.1" ||
      listener.port !== port ||
      listener.processGroupId !== record.pid
    ) {
      return "UNMANAGED";
    }
    return (await system.isHttpsReady(host)) ? "RUNNING" : "UNREADY";
  }

  async function waitForRunning() {
    const deadline = system.now() + startupWindowMilliseconds;
    while (system.now() < deadline) {
      const state = await observe();
      if (state === "RUNNING") return state;
      if (state === "UNMANAGED" || state === "STALE") {
        throw new Error(`${label} 启动期间进入不安全状态：${state}`);
      }
      await system.wait(250);
    }
    throw new Error(`${label} 未在 30 秒内通过正式 HTTPS 入口就绪。`);
  }

  async function migrateLegacyPid() {
    if (target !== "platform") return false;
    if ((await system.readPidFile()) !== undefined) return false;
    const value = await system.readLegacyPidFile?.();
    if (value === undefined) return false;
    const pid = parseLegacyPid(value);
    if (pid === undefined) {
      throw new Error("旧版 Platform PID 文件无效，拒绝迁移。");
    }
    const [process, listener] = await Promise.all([
      system.inspectProcess(pid),
      system.inspectListener(port),
    ]);
    if (process === undefined) {
      if (listener !== undefined) {
        throw new Error(
          "旧版 Platform PID 已陈旧且 5173 存在未知监听者，拒绝迁移。",
        );
      }
      await system.removeLegacyPidFile();
      return true;
    }
    if (!isLegacyPlatformProcess(process, consoleRoot, pid)) {
      throw new Error("旧版 Platform PID 指向未知进程，拒绝迁移。");
    }
    await system.writePidFile(
      JSON.stringify({
        version: 1,
        pid,
        startedAt: 0,
        processStartedAt: process.startedAt,
      }),
      { mode: 0o600 },
    );
    await system.removeLegacyPidFile();
    return true;
  }

  async function start() {
    const migrated = await migrateLegacyPid();
    let initialState = await observe();
    if (initialState === "RUNNING") {
      await system.preflight({ allowManagedConsole: true });
      return { state: initialState, exitCode: 0, changed: migrated };
    }
    if (initialState === "STALE") {
      await system.removePidFile();
      initialState = "STOPPED";
    }
    if (initialState === "STARTING" || initialState === "UNREADY") {
      await system.preflight({ allowManagedConsole: true });
      let edgeAction;
      try {
        edgeAction = await system.ensureEdge();
        return {
          state: await waitForRunning(),
          exitCode: 0,
          changed: migrated || edgeAction === "started",
        };
      } catch (error) {
        if (edgeAction === "started") await system.stopEdgeIfUnused();
        throw error;
      }
    }
    if (initialState !== "STOPPED") {
      throw new Error(`${label} 当前状态为 ${initialState}，拒绝启动。`);
    }
    await system.preflight({ allowManagedConsole: false });
    const spawned = await system.spawnConsole({
      logMode: 0o600,
      append: true,
    });
    const record = {
      version: 1,
      pid: spawned.pid,
      startedAt: system.now(),
      processStartedAt: spawned.processStartedAt,
    };
    await system.writePidFile(JSON.stringify(record), { mode: 0o600 });
    let edgeAction;
    try {
      edgeAction = await system.ensureEdge();
      return { state: await waitForRunning(), exitCode: 0, changed: true };
    } catch (error) {
      const current = await system.inspectProcess(record.pid);
      if (
        current !== undefined &&
        isConsoleProcess(current, consoleRoot, record, configuration)
      ) {
        await system.terminateConsole(record, "SIGTERM");
        for (let attempt = 0; attempt < 40; attempt += 1) {
          if ((await system.inspectProcess(record.pid)) === undefined) break;
          await system.wait(250);
        }
      }
      if ((await system.inspectProcess(record.pid)) === undefined) {
        await system.removePidFile();
      }
      if (edgeAction === "started") await system.stopEdgeIfUnused();
      throw error;
    }
  }

  async function stop() {
    await migrateLegacyPid();
    const initialState = await observe();
    if (initialState === "UNMANAGED") {
      throw new Error(`${label} 当前状态为 UNMANAGED，拒绝发送信号。`);
    }
    if (initialState === "STOPPED") {
      const edge = await system.stopEdgeIfUnused();
      return {
        state: "STOPPED",
        exitCode: 0,
        changed: edge === "stopped",
      };
    }
    if (initialState === "STALE") {
      await system.removePidFile();
      await system.stopEdgeIfUnused();
      return { state: "STOPPED", exitCode: 0, changed: true };
    }

    const record = parseManagedPid(await system.readPidFile());
    const process =
      record === undefined
        ? undefined
        : await system.inspectProcess(record.pid);
    if (
      record === undefined ||
      process === undefined ||
      !isConsoleProcess(process, consoleRoot, record, configuration)
    ) {
      throw new Error(`${label} 进程身份在停止前发生变化，拒绝发送信号。`);
    }
    await system.terminateConsole(record, "SIGTERM");
    for (let attempt = 0; attempt < 40; attempt += 1) {
      if ((await system.inspectProcess(record.pid)) === undefined) break;
      if (attempt === 39) {
        throw new Error(`${label} 未在 10 秒内响应 SIGTERM；未发送更强信号。`);
      }
      await system.wait(250);
    }
    await system.removePidFile();
    await system.stopEdgeIfUnused();
    const state = await observe();
    if (state !== "STOPPED") {
      throw new Error(`${label} 停止后状态为 ${state}，拒绝继续清理。`);
    }
    return { state, exitCode: 0, changed: true };
  }

  return {
    async run(operation) {
      if (operation === "start") return start();
      if (operation === "stop") return stop();
      if (operation === "status") {
        const state = await observe();
        return {
          state,
          exitCode: state === "UNMANAGED" || state === "UNREADY" ? 1 : 0,
        };
      }
      throw new Error(`不支持的 ${label} 生命周期操作：${operation}`);
    },
  };
}

function parseManagedPid(value) {
  let record;
  try {
    record = JSON.parse(value);
  } catch {
    return undefined;
  }
  if (
    record?.version !== 1 ||
    !Number.isSafeInteger(record.pid) ||
    record.pid <= 1 ||
    !Number.isFinite(record.startedAt) ||
    record.startedAt < 0 ||
    typeof record.processStartedAt !== "string" ||
    record.processStartedAt.length === 0
  ) {
    return undefined;
  }
  return record;
}

function parseLegacyPid(value) {
  if (typeof value !== "string" || !/^\d+$/u.test(value.trim()))
    return undefined;
  const pid = Number.parseInt(value.trim(), 10);
  return Number.isSafeInteger(pid) && pid > 1 ? pid : undefined;
}

function isConsoleProcess(process, consoleRoot, record, configuration) {
  return (
    process.pid === record.pid &&
    process.processGroupId === record.pid &&
    process.startedAt === record.processStartedAt &&
    process.cwd === consoleRoot &&
    process.command.split(/\s+/u).includes(configuration.package)
  );
}

function isLegacyPlatformProcess(process, consoleRoot, pid) {
  return (
    process.pid === pid &&
    process.processGroupId === pid &&
    process.cwd === consoleRoot &&
    process.command.split(/\s+/u).includes(frontendTargets.platform.package) &&
    typeof process.startedAt === "string" &&
    process.startedAt.length > 0
  );
}

/** 只有两个 Console 均已停止或确认陈旧时才释放共享入口；未知身份也保留 Edge。 */
export async function stopUnusedEdge({ observe, stop }) {
  for (const target of Object.keys(frontendTargets)) {
    const { state } = await observe(target);
    if (state !== "STOPPED" && state !== "STALE") return "retained";
  }
  return stop();
}
