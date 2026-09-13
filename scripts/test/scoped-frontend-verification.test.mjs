import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

// 使用真实 pnpm 执行隔离 workspace；无关包故意失败，避免仅断言命令文本。
async function workspace(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), "sf-scoped-verification-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, "scripts"));
  await mkdir(path.join(root, "consoles/node_modules"), { recursive: true });
  await cp(
    new URL("../verify-frontend-workspace.sh", import.meta.url),
    path.join(root, "scripts/verify-frontend-workspace.sh"),
  );
  await writeFile(
    path.join(root, "consoles/package.json"),
    JSON.stringify({
      private: true,
      packageManager: "pnpm@11.22.0",
      scripts: { "verify:workspace": "node -e 'process.exit(41)'" },
    }),
  );
  await writeFile(
    path.join(root, "consoles/pnpm-workspace.yaml"),
    "packages:\n  - 'packages/*'\n",
  );
  for (const [name, code] of [
    ["selected", 0],
    ["unrelated", 42],
    ["without-verify", undefined],
  ]) {
    const directory = path.join(root, "consoles/packages", name);
    await mkdir(directory, { recursive: true });
    await writeFile(
      path.join(directory, "package.json"),
      JSON.stringify({
        name: `@saas-forge/${name}`,
        version: "0.0.0",
        private: true,
        scripts:
          code === undefined
            ? {}
            : { verify: `node -e 'process.exit(${code})'` },
      }),
    );
  }
  return (...args) =>
    spawnSync(
      "bash",
      [path.join(root, "scripts/verify-frontend-workspace.sh"), ...args],
      { encoding: "utf8" },
    );
}

test("package verification runs only the selected package", async (t) => {
  const run = await workspace(t);
  const result = run("--package", "@saas-forge/selected");
  assert.equal(result.status, 0, result.stdout + result.stderr);
});

test("a selected package failure propagates to the caller", async (t) => {
  const run = await workspace(t);
  const result = run("--package", "@saas-forge/unrelated");
  assert.notEqual(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout + result.stderr, /42/u);
});

test("unknown packages fail instead of reporting an empty pass", async (t) => {
  const run = await workspace(t);
  assert.notEqual(run("--package", "@saas-forge/missing").status, 0);
});

test("the default entry still propagates full workspace failure", async (t) => {
  const run = await workspace(t);
  assert.equal(run().status, 41);
});

test("ambiguous filters and unsupported arguments are rejected", async (t) => {
  const run = await workspace(t);
  assert.equal(run("--package", "@saas-forge/*").status, 2);
  assert.equal(run("--unknown").status, 2);
});

test("a package without verify cannot silently pass", async (t) => {
  const run = await workspace(t);
  assert.notEqual(run("--package", "@saas-forge/without-verify").status, 0);
});
