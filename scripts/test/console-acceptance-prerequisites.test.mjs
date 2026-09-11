import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const artifacts = [
  "consoles/platform-console/dist/index.html",
  "consoles/tenant-console-shell/dist/index.html",
  "consoles/dist/static-remote-acceptance/v1/remote.js",
  "gateway/target/gateway.jar",
  ...["iam", "tenant-access", "entitlement", "audit"].map(
    (service) => `services/${service}-service/target/${service}.jar`,
  ),
];

// 在隔离目录运行真实入口；缺制品必须在 TLS、Docker 和数据初始化之前失败。
async function fixture(t, excluded) {
  const root = await mkdtemp(
    path.join(os.tmpdir(), "sf-acceptance-prerequisites-"),
  );
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(path.join(root, "scripts"));
  await cp(
    new URL("../verify-console-authentication-e2e.sh", import.meta.url),
    path.join(root, "scripts/verify-console-authentication-e2e.sh"),
  );
  for (const artifact of artifacts.filter((file) => file !== excluded)) {
    const file = path.join(root, artifact);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, "fixture");
  }
  return root;
}

function run(root, overrides = {}) {
  return spawnSync(
    "bash",
    [
      path.join(root, "scripts/verify-console-authentication-e2e.sh"),
      "--product",
    ],
    {
      cwd: root,
      encoding: "utf8",
      env: {
        ...process.env,
        SF_ACCEPTANCE_TARGET: "ci",
        SF_PRODUCT_CHANNEL: "",
        ...overrides,
      },
    },
  );
}

for (const channel of ["chromium", "webkit", "firefox", "msedge"]) {
  test(`product acceptance rejects retired ${channel} selection before environment setup`, async (t) => {
    const result = run(await fixture(t), { SF_PRODUCT_CHANNEL: channel });
    assert.equal(result.status, 2, result.stdout + result.stderr);
    assert.match(result.stderr, /当前仅接受 chrome/u);
    assert.doesNotMatch(result.stdout, /EVIDENCE:|PASS:|RUN:/u);
  });
}

test("fresh product acceptance rejects a missing service artifact before environment setup", async (t) => {
  const root = await fixture(t, "gateway/target/gateway.jar");
  const result = run(root);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /BLOCKED:.*gateway.*JAR/u);
  assert.doesNotMatch(result.stdout, /EVIDENCE:|PASS:|RUN:/u);
});

for (const artifact of artifacts.filter(
  (file) => !file.startsWith("gateway/"),
)) {
  test(`fresh product acceptance rejects missing ${artifact}`, async (t) => {
    const result = run(await fixture(t, artifact));
    assert.equal(result.status, 1, result.stdout + result.stderr);
    assert.match(result.stderr, /BLOCKED:/u);
    assert.doesNotMatch(result.stdout, /EVIDENCE:|PASS:|RUN:/u);
  });
}

test("fresh product acceptance rejects ambiguous runtime jars", async (t) => {
  const root = await fixture(t);
  await writeFile(
    path.join(root, "gateway/target/old-gateway.jar"),
    "old fixture",
  );
  const result = run(root);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /BLOCKED:.*gateway.*JAR/u);
  assert.doesNotMatch(result.stdout, /EVIDENCE:|PASS:|RUN:/u);
});
