import assert from "node:assert/strict";
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
  assert.deepEqual(localDevelopmentPlan(["frontend"])[0].arguments, ["start"]);
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
