import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repository = fileURLToPath(new URL("../../", import.meta.url));

test("backend-local skips the frontend execution even with an incompatible Node", async (t) => {
  const bin = await mkdtemp(path.join(os.tmpdir(), "sf-backend-verification-"));
  t.after(() => rm(bin, { recursive: true, force: true }));
  // 仅在外部工具边界注入不受支持的 Node；实际 Maven 和正式前端入口保持不变。
  await writeFile(path.join(bin, "node"), "#!/bin/sh\necho v0.0.0\n", {
    mode: 0o700,
  });
  const result = spawnSync(
    path.join(repository, "mvnw"),
    [
      "--batch-mode",
      "--no-transfer-progress",
      "-pl",
      "contracts/openapi",
      "-Pbackend-local",
      "exec:exec@verify-consoles",
    ],
    {
      cwd: repository,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` },
      encoding: "utf8",
      timeout: 120_000,
    },
  );
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const full = spawnSync(
    path.join(repository, "mvnw"),
    [
      "--batch-mode",
      "--no-transfer-progress",
      "-pl",
      "contracts/openapi",
      "exec:exec@verify-consoles",
    ],
    {
      cwd: repository,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` },
      encoding: "utf8",
      timeout: 120_000,
    },
  );
  assert.equal(full.status, 1, full.stdout + full.stderr);
  assert.match(full.stderr, /found v0\.0\.0/u);
});
