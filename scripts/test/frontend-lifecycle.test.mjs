import assert from "node:assert/strict";
import test from "node:test";

import { createFrontendLifecycle } from "../frontend-lifecycle.mjs";

const repositoryRoot = "/workspace/saas-forge";
const managedPid = 4100;

function adapter({
  pidFile = undefined,
  process = undefined,
  listener = undefined,
  httpsReady = false,
} = {}) {
  return {
    now: () => 60_000,
    readPidFile: async () => pidFile,
    readLegacyPidFile: async () => undefined,
    inspectProcess: async () => process,
    inspectListener: async () => listener,
    isHttpsReady: async () => httpsReady,
  };
}

function managedProcess(overrides = {}) {
  return {
    pid: managedPid,
    processGroupId: managedPid,
    startedAt: "process-start-a",
    command:
      "pnpm --filter @saas-forge/platform-console run dev -- --host 127.0.0.1 --port 5173 --strictPort",
    cwd: `${repositoryRoot}/consoles`,
    ...overrides,
  };
}

function managedPidFile(startedAt = 0) {
  return JSON.stringify({
    version: 1,
    pid: managedPid,
    startedAt,
    processStartedAt: "process-start-a",
  });
}

test("reports every Platform lifecycle state through the public status interface", async () => {
  const cases = [
    ["STOPPED", adapter()],
    ["STALE", adapter({ pidFile: managedPidFile() })],
    [
      "UNMANAGED",
      adapter({
        pidFile: managedPidFile(),
        process: managedProcess({ command: "node unrelated-server.mjs" }),
      }),
    ],
    [
      "STARTING",
      adapter({
        pidFile: managedPidFile(50_000),
        process: managedProcess(),
      }),
    ],
    [
      "UNREADY",
      adapter({
        pidFile: managedPidFile(),
        process: managedProcess(),
      }),
    ],
    [
      "RUNNING",
      adapter({
        pidFile: managedPidFile(),
        process: managedProcess(),
        listener: {
          address: "127.0.0.1",
          port: 5173,
          processGroupId: managedPid,
        },
        httpsReady: true,
      }),
    ],
  ];

  for (const [expected, system] of cases) {
    const lifecycle = createFrontendLifecycle({
      target: "platform",
      repositoryRoot,
      system,
    });
    assert.equal((await lifecycle.run("status")).state, expected);
  }
});

test("starts a stopped Platform with a restricted managed PID and append-only log", async () => {
  let pidFile;
  let process;
  let listener;
  let edgeReady = false;
  let spawnOptions;
  const system = {
    now: () => 60_000,
    readPidFile: async () => pidFile,
    inspectProcess: async () => process,
    inspectListener: async () => listener,
    isHttpsReady: async () => edgeReady,
    preflight: async () => undefined,
    spawnConsole: async (options) => {
      spawnOptions = options;
      process = managedProcess();
      listener = {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      };
      return { pid: managedPid, processStartedAt: "process-start-a" };
    },
    writePidFile: async (value, options) => {
      assert.deepEqual(options, { mode: 0o600 });
      pidFile = value;
    },
    ensureEdge: async () => {
      edgeReady = true;
      return "started";
    },
    wait: async () => undefined,
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  const result = await lifecycle.run("start");

  assert.equal(result.state, "RUNNING");
  assert.equal(JSON.parse(pidFile).pid, managedPid);
  assert.deepEqual(spawnOptions, { logMode: 0o600, append: true });
});

test("classifies a reused PID as unmanaged even when command and directory match", async () => {
  const system = adapter({
    pidFile: managedPidFile(),
    process: managedProcess({ startedAt: "process-start-b" }),
  });
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  assert.equal((await lifecycle.run("status")).state, "UNMANAGED");
});

test("keeps start idempotent and never mutates an unmanaged Platform", async () => {
  let mutations = 0;
  let preflights = 0;
  const runningSystem = {
    ...adapter({
      pidFile: managedPidFile(),
      process: managedProcess(),
      listener: {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      },
      httpsReady: true,
    }),
    preflight: async () => {
      preflights += 1;
    },
    spawnConsole: async () => {
      mutations += 1;
    },
    writePidFile: async () => {
      mutations += 1;
    },
    ensureEdge: async () => {
      mutations += 1;
    },
  };
  const running = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: runningSystem,
  });
  assert.deepEqual(await running.run("start"), {
    state: "RUNNING",
    exitCode: 0,
    changed: false,
  });
  assert.equal(preflights, 1);
  assert.equal(mutations, 0);

  const unmanagedSystem = {
    ...adapter({
      listener: {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: 9999,
      },
    }),
    preflight: async () => {
      mutations += 1;
    },
    spawnConsole: async () => {
      mutations += 1;
    },
  };
  const unmanaged = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: unmanagedSystem,
  });
  await assert.rejects(() => unmanaged.run("start"), /UNMANAGED/u);
  assert.equal(mutations, 0);
});

test("stops only the identity-verified Platform process and then stops the unused Edge", async () => {
  let pidFile = managedPidFile();
  let process = managedProcess();
  let listener = {
    address: "127.0.0.1",
    port: 5173,
    processGroupId: managedPid,
  };
  let edgeReady = true;
  let signal;
  let terminationChecks = 0;
  let terminating = false;
  const system = {
    now: () => 60_000,
    readPidFile: async () => pidFile,
    inspectProcess: async () => {
      if (terminating && terminationChecks++ >= 1) process = undefined;
      return process;
    },
    inspectListener: async () => {
      if (process === undefined) listener = undefined;
      return listener;
    },
    isHttpsReady: async () => edgeReady,
    terminateConsole: async (record, requestedSignal) => {
      signal = { record, requestedSignal };
      terminating = true;
    },
    removePidFile: async () => {
      pidFile = undefined;
    },
    stopEdgeIfUnused: async () => {
      edgeReady = false;
      return "stopped";
    },
    wait: async () => undefined,
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  const result = await lifecycle.run("stop");

  assert.deepEqual(result, { state: "STOPPED", exitCode: 0, changed: true });
  assert.equal(signal.requestedSignal, "SIGTERM");
  assert.equal(signal.record.pid, managedPid);
  assert.equal(process, undefined);
  assert.equal(pidFile, undefined);
  assert.equal(edgeReady, false);
});

test("adopts a matching legacy Platform PID and preserves its log", async () => {
  let pidFile;
  let legacyPidFile = `${managedPid}\n`;
  const legacyLog = "previous diagnostic evidence\n";
  let edgeReady = false;
  const system = {
    ...adapter({
      process: managedProcess(),
      listener: {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      },
      httpsReady: false,
    }),
    readPidFile: async () => pidFile,
    readLegacyPidFile: async () => legacyPidFile,
    writePidFile: async (value) => {
      pidFile = value;
    },
    removeLegacyPidFile: async () => {
      legacyPidFile = undefined;
    },
    readLegacyLog: async () => legacyLog,
    isHttpsReady: async () => edgeReady,
    preflight: async () => undefined,
    ensureEdge: async () => {
      edgeReady = true;
      return "started";
    },
    wait: async () => undefined,
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  const result = await lifecycle.run("start");

  assert.deepEqual(result, { state: "RUNNING", exitCode: 0, changed: true });
  assert.equal(JSON.parse(pidFile).processStartedAt, "process-start-a");
  assert.equal(legacyPidFile, undefined);
  assert.equal(await system.readLegacyLog(), legacyLog);
});

test("observes a matching legacy PID without mutating it during status", async () => {
  let legacyPidFile = `${managedPid}\n`;
  const system = {
    ...adapter({
      process: managedProcess(),
      listener: {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      },
      httpsReady: true,
    }),
    readLegacyPidFile: async () => legacyPidFile,
    removeLegacyPidFile: async () => {
      legacyPidFile = undefined;
    },
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  assert.equal((await lifecycle.run("status")).state, "RUNNING");
  assert.equal(legacyPidFile, `${managedPid}\n`);
});

test("rolls back only resources created by a failed Platform start", async () => {
  let now = 60_000;
  let pidFile;
  let process;
  let listener;
  let edgeRunning = false;
  const system = {
    now: () => now,
    readPidFile: async () => pidFile,
    readLegacyPidFile: async () => undefined,
    inspectProcess: async () => process,
    inspectListener: async () => listener,
    isHttpsReady: async () => false,
    preflight: async () => undefined,
    spawnConsole: async () => {
      process = managedProcess();
      listener = {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      };
      return { pid: managedPid, processStartedAt: "process-start-a" };
    },
    writePidFile: async (value) => {
      pidFile = value;
    },
    ensureEdge: async () => {
      edgeRunning = true;
      return "started";
    },
    terminateConsole: async () => {
      process = undefined;
      listener = undefined;
    },
    removePidFile: async () => {
      pidFile = undefined;
    },
    stopEdgeIfUnused: async () => {
      edgeRunning = false;
      return "stopped";
    },
    wait: async (milliseconds) => {
      now += milliseconds;
    },
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  await assert.rejects(() => lifecycle.run("start"), /30 秒/u);

  assert.equal(process, undefined);
  assert.equal(pidFile, undefined);
  assert.equal(edgeRunning, false);
});

test("preserves a pre-existing Edge when Platform readiness fails", async () => {
  let now = 60_000;
  let pidFile;
  let process;
  let listener;
  let edgeRunning = true;
  const system = {
    now: () => now,
    readPidFile: async () => pidFile,
    readLegacyPidFile: async () => undefined,
    inspectProcess: async () => process,
    inspectListener: async () => listener,
    isHttpsReady: async () => false,
    preflight: async () => undefined,
    spawnConsole: async () => {
      process = managedProcess();
      listener = {
        address: "127.0.0.1",
        port: 5173,
        processGroupId: managedPid,
      };
      return { pid: managedPid, processStartedAt: "process-start-a" };
    },
    writePidFile: async (value) => {
      pidFile = value;
    },
    ensureEdge: async () => "reused",
    terminateConsole: async () => {
      process = undefined;
      listener = undefined;
    },
    removePidFile: async () => {
      pidFile = undefined;
    },
    stopEdgeIfUnused: async () => {
      edgeRunning = false;
      return "stopped";
    },
    wait: async (milliseconds) => {
      now += milliseconds;
    },
  };
  const lifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system,
  });

  await assert.rejects(() => lifecycle.run("start"), /30 秒/u);

  assert.equal(pidFile, undefined);
  assert.equal(edgeRunning, true);
});

test("cleans a stale legacy PID but blocks one that points to an unknown process", async () => {
  let stalePid = `${managedPid}\n`;
  const staleSystem = {
    ...adapter(),
    readLegacyPidFile: async () => stalePid,
    removeLegacyPidFile: async () => {
      stalePid = undefined;
    },
    stopEdgeIfUnused: async () => "already-stopped",
  };
  const staleLifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: staleSystem,
  });
  assert.equal((await staleLifecycle.run("stop")).state, "STOPPED");
  assert.equal(stalePid, undefined);

  let mutations = 0;
  const unknownSystem = {
    ...adapter({
      process: managedProcess({ command: "node unrelated-server.mjs" }),
    }),
    readLegacyPidFile: async () => `${managedPid}\n`,
    removeLegacyPidFile: async () => {
      mutations += 1;
    },
    writePidFile: async () => {
      mutations += 1;
    },
    terminateConsole: async () => {
      mutations += 1;
    },
  };
  const unknownLifecycle = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: unknownSystem,
  });
  await assert.rejects(() => unknownLifecycle.run("start"), /未知进程/u);
  assert.equal(mutations, 0);
});

test("keeps stop idempotent and never signals an unmanaged process", async () => {
  let signals = 0;
  let edgeStops = 0;
  const stoppedSystem = {
    ...adapter(),
    stopEdgeIfUnused: async () => {
      edgeStops += 1;
      return "already-stopped";
    },
  };
  const stopped = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: stoppedSystem,
  });
  assert.deepEqual(await stopped.run("stop"), {
    state: "STOPPED",
    exitCode: 0,
    changed: false,
  });
  assert.equal(edgeStops, 1);

  const unmanagedSystem = {
    ...adapter({
      pidFile: managedPidFile(),
      process: managedProcess({
        cwd: "/workspace/another-repository/consoles",
      }),
    }),
    terminateConsole: async () => {
      signals += 1;
    },
  };
  const unmanaged = createFrontendLifecycle({
    target: "platform",
    repositoryRoot,
    system: unmanagedSystem,
  });
  await assert.rejects(() => unmanaged.run("stop"), /UNMANAGED/u);
  assert.equal(signals, 0);
});

test("Tenant validates all states and rejects Platform, reused and foreign PIDs", async () => {
  const tenantProcess = managedProcess({
    command: "pnpm --filter @saas-forge/tenant-console-shell run dev",
  });
  const listener = {
    address: "127.0.0.1",
    port: 5174,
    processGroupId: managedPid,
  };
  const cases = [
    ["STOPPED", {}],
    ["STALE", { pidFile: managedPidFile() }],
    ["UNMANAGED", { listener }],
    ["STARTING", { pidFile: managedPidFile(50_000), process: tenantProcess }],
    [
      "UNREADY",
      { pidFile: managedPidFile(), process: tenantProcess, listener },
    ],
    [
      "RUNNING",
      {
        pidFile: managedPidFile(),
        process: tenantProcess,
        listener,
        httpsReady: true,
      },
    ],
    ...[
      managedProcess(),
      { ...tenantProcess, startedAt: "reused" },
      { ...tenantProcess, cwd: "/another/repository/consoles" },
      {
        ...tenantProcess,
        command:
          "pnpm --filter @saas-forge/tenant-console-shell-unrelated run dev",
      },
    ].map((process) => [
      "UNMANAGED",
      { pidFile: managedPidFile(), process, listener, httpsReady: true },
    ]),
  ];
  for (const [expected, options] of cases) {
    const system = adapter(options);
    system.readLegacyPidFile = () => {
      throw new Error("Tenant must not access legacy Platform PID");
    };
    system.isHttpsReady = async (host) => {
      assert.equal(host, "console.saasforge.test");
      return options.httpsReady ?? false;
    };
    const lifecycle = createFrontendLifecycle({
      repositoryRoot,
      system,
      target: "tenant",
    });
    assert.equal((await lifecycle.run("status")).state, expected);
    if (expected === "UNMANAGED") {
      system.terminateConsole = () => {
        throw new Error("must not signal unknown process");
      };
      await assert.rejects(() => lifecycle.run("start"), /UNMANAGED/u);
      await assert.rejects(() => lifecycle.run("stop"), /UNMANAGED/u);
    }
  }
});

test("two Consoles reuse Edge, stop independently and preserve Platform after Tenant failure", async () => {
  const { stopUnusedEdge, frontendTargets } =
    await import("../frontend-lifecycle.mjs");
  const consoles = {};
  const records = {};
  let edge = false;
  let edgeStarts = 0;
  let edgeStops = 0;
  let tenantFails = false;
  let now = 60_000;
  for (const [target, configuration] of Object.entries(frontendTargets)) {
    const state = (records[target] = {});
    const system = {
      now: () => now,
      readPidFile: async () => state.pidFile,
      inspectProcess: async () => state.process,
      inspectListener: async () =>
        state.process && {
          address: "127.0.0.1",
          port: configuration.port,
          processGroupId: state.process.pid,
        },
      isHttpsReady: async () => edge && !(target === "tenant" && tenantFails),
      preflight: async () => {},
      spawnConsole: async (options) => {
        assert.deepEqual(options, { logMode: 0o600, append: true });
        state.process = managedProcess({
          pid: configuration.port,
          processGroupId: configuration.port,
          command: "pnpm --filter " + configuration.package + " run dev",
        });
        return {
          pid: configuration.port,
          processStartedAt: state.process.startedAt,
        };
      },
      writePidFile: async (value, options) => {
        assert.equal(options.mode, 0o600);
        state.pidFile = value;
      },
      removePidFile: async () => {
        state.pidFile = undefined;
      },
      ensureEdge: async () => {
        if (edge) return "reused";
        edge = true;
        edgeStarts++;
        return "started";
      },
      terminateConsole: async (record, signal) => {
        assert.equal(record.pid, configuration.port);
        assert.equal(signal, "SIGTERM");
        state.process = undefined;
      },
      stopEdgeIfUnused: () =>
        stopUnusedEdge({
          observe: (name) => consoles[name].run("status"),
          stop: async () => {
            if (!edge) return "already-stopped";
            edge = false;
            edgeStops++;
            return "stopped";
          },
        }),
      wait: async (duration) => {
        now += duration;
      },
    };
    consoles[target] = createFrontendLifecycle({
      repositoryRoot,
      system,
      target,
    });
  }
  await consoles.platform.run("start");
  const platformPid = records.platform.pidFile;
  await consoles.tenant.run("start");
  await consoles.tenant.run("start");
  assert.equal(edgeStarts, 1);
  await consoles.tenant.run("stop");
  await consoles.tenant.run("stop");
  assert.equal(edgeStops, 0);
  assert.equal((await consoles.platform.run("status")).state, "RUNNING");
  tenantFails = true;
  await assert.rejects(() => consoles.tenant.run("start"), /30 秒/u);
  assert.equal(records.platform.pidFile, platformPid);
  assert.equal(edgeStops, 0);
  tenantFails = false;
  await consoles.tenant.run("start");
  await consoles.platform.run("stop");
  assert.equal((await consoles.tenant.run("status")).state, "RUNNING");
  assert.equal(edgeStops, 0);
  await consoles.tenant.run("stop");
  assert.equal(edgeStops, 1);
});

test("shared Edge is retained for unknown, starting and unready peers", async () => {
  const { stopUnusedEdge } = await import("../frontend-lifecycle.mjs");
  for (const state of ["UNMANAGED", "STARTING", "UNREADY", "RUNNING"]) {
    assert.equal(
      await stopUnusedEdge({
        observe: async (target) => ({
          state: target === "platform" ? "STOPPED" : state,
        }),
        stop: async () => {
          throw new Error("must retain Edge");
        },
      }),
      "retained",
    );
  }
});
