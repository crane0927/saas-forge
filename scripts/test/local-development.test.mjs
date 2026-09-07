import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  executeLocalDevelopmentPlan,
  localDevelopmentPlan,
} from "../local-development.mjs";

test("provides one daily interface for setup, frontend, replacement, and restore", () => {
  assert.deepEqual(
    localDevelopmentPlan(["setup"]).map(({ script, arguments: arguments_ }) => [
      script,
      ...arguments_,
    ]),
    [
      ["local-https-development.sh", "setup"],
      ["local-https-development.sh", "hosts"],
      ["local-https-development.sh", "trust-ca"],
    ],
  );
  for (const operation of ["start", "status", "stop"]) {
    assert.deepEqual(
      localDevelopmentPlan(["frontend", operation, "platform"]),
      [
        {
          script: "local-https-development.sh",
          arguments: [operation, "platform"],
        },
      ],
    );
  }
  assert.equal(localDevelopmentPlan(["frontend"]), undefined);
  assert.equal(localDevelopmentPlan(["frontend", "start"]), undefined);
  assert.equal(
    localDevelopmentPlan(["frontend", "start", "platform", "extra"]),
    undefined,
  );
  assert.deepEqual(
    localDevelopmentPlan(["replace", "audit-service"])[0].arguments,
    ["replace", "audit-service"],
  );
  assert.deepEqual(localDevelopmentPlan(["restore", "gateway"])[0].arguments, [
    "restore",
    "gateway",
  ]);
  assert.equal(localDevelopmentPlan(["replace", "unknown-service"]), undefined);
});

test("status and doctor cover all five targets even when one check fails", () => {
  const status = localDevelopmentPlan(["status"]);
  const doctor = localDevelopmentPlan(["doctor"]);
  assert.deepEqual(
    status.map((step) => step.arguments.at(-1)),
    [
      "gateway",
      "iam-service",
      "tenant-access-service",
      "entitlement-service",
      "audit-service",
    ],
  );
  assert.equal(doctor.length, 6);
  assert.ok([...status, ...doctor].every((step) => step.continueOnFailure));

  let calls = 0;
  const exitCode = executeLocalDevelopmentPlan(status, () => ({
    status: calls++ === 0 ? 1 : 0,
  }));
  assert.equal(calls, 5);
  assert.equal(exitCode, 1);
});

test("rejects incomplete and obsolete frontend CLI invocations with safe usage", () => {
  const script = new URL("../local-development.sh", import.meta.url).pathname;
  for (const arguments_ of [
    ["frontend"],
    ["frontend", "start"],
    ["frontend", "start", "platform", "extra"],
    ["frontend", "start", "unknown"],
  ]) {
    const result = spawnSync("bash", [script, ...arguments_], {
      encoding: "utf8",
    });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /frontend <start\|status\|stop> platform/u);
  }
});

test("the acceptance matrix covers every target and preserves images and volumes", async () => {
  const matrix = await readFile(
    new URL("../verify-local-development-matrix.sh", import.meta.url),
    "utf8",
  );
  for (const target of [
    "gateway",
    "iam-service",
    "tenant-access-service",
    "entitlement-service",
    "audit-service",
  ]) {
    assert.match(matrix, new RegExp(`\\b${target}\\b`, "u"));
  }
  assert.match(matrix, /snapshot_images/u);
  assert.match(matrix, /snapshot_volumes/u);
  assert.match(matrix, /verify-local-development-security-browser\.mjs/u);
  assert.doesNotMatch(matrix, /docker\s+(?:compose\s+)?build/u);
  assert.doesNotMatch(matrix, /down\s+--volumes/u);
});
