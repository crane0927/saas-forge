import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const source = await readFile(
  new URL("../verify-tenant-lifecycle-e2e.sh", import.meta.url),
  "utf8",
);
const probe = source.match(
  /^verify_revocation_index_not_ready\(\) [({]\n[\s\S]*?^[)}]$/m,
)?.[0];
assert.ok(probe, "the real Tenant Fresh probe must be present");

async function run(t, failure = "") {
  const root = await mkdtemp(path.join(os.tmpdir(), "sf-revocation-probe-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  await writeFile(
    path.join(root, "redis-cli"),
    `#!/bin/sh
set -eu
test "$1" = -e || exit 48
case "$*" in
  *"ACL SETUSER default +set"*) echo true > set-allowed ;;
  *"ACL SETUSER default -set"*)
    test "$FAILURE" != deny || exit 42
    echo false > set-allowed ;;
  *"MSET"*)
    test "$FAILURE" != inject || exit 43
    echo 0 > ready ;;
  *) exit 44 ;;
esac
`,
    { mode: 0o700 },
  );
  // 执行真实 Bash 探针；只替换 Compose/HTTP 边界，将恢复 tick 固定在请求前。
  const result = spawnSync(
    "bash",
    [
      "-c",
      `
set -eu
cd "$FIXTURE_ROOT"
echo 1 > ready
echo true > set-allowed
probe_body=fixture
platform_token=fixture
compose() {
  test "$1 $2 $3" = "exec -T redis" || return 44
  shift 3
  "$@"
}
uuid_v7() { echo fixture; }
request() {
  if test "$(cat set-allowed)" = true; then echo 1 > ready; fi
  actual=503
  if test "$(cat ready)" = 1; then actual=201; fi
  printf 'expected=%s actual=%s\\n' "$1" "$actual"
  test "$1" = "$actual" || return 45
  test "$FAILURE" != request || return 46
}
assert_json() { test "$FAILURE" != response || return 47; }
wait_for_redis_revocation_ready() { test "$(cat ready)" = 1; }
${probe}
verify_revocation_index_not_ready
`,
    ],
    {
      encoding: "utf8",
      timeout: 5_000,
      env: {
        ...process.env,
        PATH: `${root}:${process.env.PATH}`,
        REDIS_PASSWORD: "fixture",
        FIXTURE_ROOT: root,
        FAILURE: failure,
      },
    },
  );
  return {
    ...result,
    setAllowed: (await readFile(path.join(root, "set-allowed"), "utf8")).trim(),
  };
}

test("not-ready probe holds the fault across a competing recovery tick", async (t) => {
  const result = await run(t);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /expected=503 actual=503/u);
  assert.equal(result.setAllowed, "true");
});

for (const [failure, exitCode] of [
  ["deny", 42],
  ["inject", 43],
  ["request", 46],
  ["response", 47],
]) {
  test(`not-ready probe propagates ${failure} failure and releases the writer`, async (t) => {
    const result = await run(t, failure);
    assert.equal(result.status, exitCode, result.stdout + result.stderr);
    assert.equal(result.setAllowed, "true");
  });
}
