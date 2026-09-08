import assert from "node:assert/strict";
import test from "node:test";
import * as frontend from "../frontend-lifecycle.mjs";

const repositoryRoot = "/workspace/saas-forge";
const targets = ["platform", "tenant"];

// 系统边界夹具：真实生命周期读取 PID、进程、监听与 HTTPS；不替换内部生命周期。
function fixture(initial = {}, initialEdge = "STOPPED", failure) {
  let clock = 60_000;
  let nextPid = 5000;
  let edgeState = initialEdge;
  const processes = {};
  const pidFiles = {};
  const signals = [];
  const starts = [];
  const edgeStops = [];
  const systems = {};
  for (const [index, target] of targets.entries()) {
    const configuration = frontend.frontendTargets[target];
    const state = initial[target] ?? "STOPPED";
    if (state !== "STOPPED") {
      const pid = 4100 + index;
      pidFiles[target] = JSON.stringify({
        version: 1,
        pid,
        startedAt: state === "STARTING" ? clock : 0,
        processStartedAt: `born-${pid}`,
      });
      if (state !== "STALE")
        processes[target] = {
          pid,
          processGroupId: pid,
          startedAt: `born-${pid}`,
          cwd: `${repositoryRoot}/consoles`,
          command: state === "UNMANAGED" ? "unrelated" : configuration.package,
          listening: state !== "STARTING",
          ready: state === "RUNNING",
        };
    }
    systems[target] = {
      now: () => clock,
      wait: async (ms) => {
        clock += ms;
      },
      readPidFile: async () => pidFiles[target],
      inspectProcess: async (pid) =>
        processes[target]?.pid === pid ? processes[target] : undefined,
      inspectListener: async () =>
        processes[target]?.listening
          ? {
              address: "127.0.0.1",
              port: configuration.port,
              processGroupId: processes[target].pid,
            }
          : undefined,
      isHttpsReady: async () =>
        edgeState === "RUNNING" && processes[target]?.ready,
      preflight: async () => {
        if (failure === `${target}:preflight`) throw new Error(failure);
      },
      spawnConsole: async () => {
        if (failure === `${target}:spawn`) throw new Error(failure);
        const pid = nextPid++;
        processes[target] = {
          pid,
          processGroupId: pid,
          startedAt: `born-${pid}`,
          cwd: `${repositoryRoot}/consoles`,
          command: configuration.package,
          listening: true,
          ready: failure !== `${target}:ready`,
        };
        starts.push(target);
        return { pid, processStartedAt: `born-${pid}` };
      },
      writePidFile: async (value) => {
        if (failure === `${target}:pid`) throw new Error(failure);
        pidFiles[target] = value;
      },
      removePidFile: async () => {
        delete pidFiles[target];
      },
      terminateConsole: async (record) => {
        assert.equal(processes[target]?.pid, record.pid);
        signals.push(target);
        delete processes[target];
      },
    };
  }
  const edge = {
    status: async () => ({
      state: edgeState,
      exitCode: ["STOPPED", "RUNNING"].includes(edgeState) ? 0 : 1,
      identity: edgeState === "STOPPED" ? undefined : "edge-1",
    }),
    ensure: async (acquired) => {
      if (edgeState === "RUNNING") return;
      if (failure === "edge:spawn") throw new Error(failure);
      edgeState = "RUNNING";
      acquired("edge-1");
      if (failure === "edge:ready") throw new Error(failure);
    },
    stop: async (identity) => {
      assert.equal(identity, "edge-1");
      edgeStops.push(identity);
      edgeState = "STOPPED";
    },
  };
  return {
    lifecycle: () =>
      frontend.createFrontendOrchestration({ repositoryRoot, systems, edge }),
    snapshot: () => ({ processes: structuredClone(processes), edgeState }),
    signals,
    starts,
    edgeStops,
  };
}

test("all status reports both fixed endpoints and Edge without mutating resources", async () => {
  for (const state of [
    "STOPPED",
    "STALE",
    "STARTING",
    "UNREADY",
    "UNMANAGED",
    "RUNNING",
  ]) {
    const environment = fixture({ platform: state, tenant: state }, "RUNNING");
    const before = environment.snapshot();
    const result = await environment.lifecycle().run("status");
    assert.deepEqual(result.consoles, {
      platform: {
        state,
        exitCode: ["UNREADY", "UNMANAGED"].includes(state) ? 1 : 0,
        port: 5173,
        host: "platform.saasforge.test",
      },
      tenant: {
        state,
        exitCode: ["UNREADY", "UNMANAGED"].includes(state) ? 1 : 0,
        port: 5174,
        host: "console.saasforge.test",
      },
    });
    assert.equal(result.edge.state, "RUNNING");
    assert.equal(
      result.exitCode,
      ["UNREADY", "UNMANAGED"].includes(state) ? 1 : 0,
    );
    assert.deepEqual(environment.snapshot(), before);
  }
});

test("start all handles every stopped/running combination and repeats without restarting", async () => {
  for (const platform of ["STOPPED", "RUNNING"]) {
    for (const tenant of ["STOPPED", "RUNNING"]) {
      for (const edge of ["STOPPED", "RUNNING"]) {
        const environment = fixture({ platform, tenant }, edge);
        const lifecycle = environment.lifecycle();
        const result = await lifecycle.run("start");
        assert.equal(result.exitCode, 0);
        assert.equal(result.consoles.platform.state, "RUNNING");
        assert.equal(result.consoles.tenant.state, "RUNNING");
        assert.deepEqual(
          environment.starts,
          targets.filter(
            (target) => ({ platform, tenant })[target] === "STOPPED",
          ),
        );
        const before = environment.snapshot();
        assert.equal((await lifecycle.run("start")).exitCode, 0);
        assert.deepEqual(environment.snapshot(), before);
        assert.deepEqual(environment.signals, []);
        assert.deepEqual(environment.edgeStops, []);
      }
    }
  }
});

test("a failure at either Console or Edge rolls back only resources acquired by this invocation", async () => {
  for (const initial of [{}, { platform: "RUNNING" }, { tenant: "RUNNING" }]) {
    for (const edge of ["STOPPED", "RUNNING"]) {
      for (const failure of [
        "platform:preflight",
        "tenant:preflight",
        "platform:spawn",
        "tenant:spawn",
        "platform:pid",
        "tenant:pid",
        "platform:ready",
        "tenant:ready",
        "edge:spawn",
        "edge:ready",
      ]) {
        const [target, phase] = failure.split(":");
        if (target === "edge" && edge === "RUNNING") continue;
        if (initial[target] === "RUNNING" && phase !== "preflight") continue;
        const environment = fixture(initial, edge, failure);
        const before = environment.snapshot();
        await assert.rejects(
          environment.lifecycle().run("start"),
          undefined,
          `${JSON.stringify(initial)} ${edge} ${failure}`,
        );
        assert.deepEqual(
          environment.snapshot(),
          before,
          `${JSON.stringify(initial)} ${edge} ${failure}`,
        );
        assert.ok(
          environment.signals.every((name) => initial[name] !== "RUNNING"),
        );
      }
    }
  }
});

test("stop all releases managed Consoles and Edge but refuses unknown identities before mutation", async () => {
  for (const platform of [
    "STOPPED",
    "STALE",
    "STARTING",
    "RUNNING",
    "UNREADY",
    "UNMANAGED",
  ]) {
    for (const tenant of [
      "STOPPED",
      "STALE",
      "STARTING",
      "RUNNING",
      "UNREADY",
      "UNMANAGED",
    ]) {
      for (const edge of ["STOPPED", "RUNNING"]) {
        const environment = fixture({ platform, tenant }, edge);
        const before = environment.snapshot();
        if ([platform, tenant].includes("UNMANAGED")) {
          await assert.rejects(environment.lifecycle().run("stop"));
          assert.deepEqual(environment.snapshot(), before);
        } else {
          const result = await environment.lifecycle().run("stop");
          assert.equal(result.exitCode, 0);
          assert.deepEqual(environment.snapshot(), {
            processes: {},
            edgeState: "STOPPED",
          });
        }
      }
    }
  }
});

test("all operations classify every pair of initial Console states and reject unsafe Edge without mutation", async () => {
  for (const platform of [
    "STOPPED",
    "STALE",
    "STARTING",
    "RUNNING",
    "UNREADY",
    "UNMANAGED",
  ]) {
    for (const tenant of [
      "STOPPED",
      "STALE",
      "STARTING",
      "RUNNING",
      "UNREADY",
      "UNMANAGED",
    ]) {
      for (const edge of [
        "STOPPED",
        "RUNNING",
        "INVALID",
        "UNMANAGED",
        "UNREADY",
      ]) {
        const environment = fixture({ platform, tenant }, edge);
        const before = environment.snapshot();
        const result = await environment.lifecycle().run("status");
        assert.equal(
          result.exitCode,
          (edge !== "RUNNING" && edge !== "STOPPED") ||
            [platform, tenant].some((state) =>
              ["UNREADY", "UNMANAGED"].includes(state),
            ) ||
            (edge === "STOPPED" && [platform, tenant].includes("RUNNING"))
            ? 1
            : 0,
        );
        assert.deepEqual(environment.snapshot(), before);
        const canStart =
          ["STOPPED", "RUNNING"].includes(edge) &&
          ![platform, tenant].some((state) =>
            ["UNMANAGED", "UNREADY", "STARTING"].includes(state),
          );
        if (canStart) {
          const started = await environment.lifecycle().run("start");
          assert.equal(started.consoles.platform.state, "RUNNING");
          assert.equal(started.consoles.tenant.state, "RUNNING");
        } else {
          await assert.rejects(environment.lifecycle().run("start"));
          assert.deepEqual(environment.snapshot(), before);
        }
      }
    }
  }
});

test("stop all can recover an identity-verified but unready Edge", async () => {
  const environment = fixture(
    { platform: "RUNNING", tenant: "RUNNING" },
    "UNREADY",
  );
  const result = await environment.lifecycle().run("stop");
  assert.equal(result.exitCode, 0);
  assert.deepEqual(environment.snapshot(), {
    processes: {},
    edgeState: "STOPPED",
  });
});
