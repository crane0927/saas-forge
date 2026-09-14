import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));

for (const acceptance of [false, true]) {
  test(`JWT initialization routes database and migration commands to ${acceptance ? "one acceptance project" : "their independent owners"}`, async () => {
    const temporary = await mkdtemp(path.join(os.tmpdir(), "compose-initialization-"));
    try {
      const log = path.join(temporary, "calls.jsonl");
      await writeFile(path.join(temporary, "docker"), `#!${process.execPath} --
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.TEST_COMPOSE_LOG, JSON.stringify(args) + '\\n');
if (args.includes('config')) {
  console.log(JSON.stringify({ services: { 'iam-service': {
    environment: { IAM_JWT_PEM_KEY_VERSION_REF: 'test/1' },
    volumes: [{ target: '/run/secrets/iam-jwt-private-key.pem', source: '/unused/test-key.pem' }]
  } } }));
}
// 到达数据库读取后终止，保证测试不会生成或修改任何密钥。
if (args.includes('exec')) process.exit(17);
`, { mode: 0o755 });
      const env = { PATH: `${temporary}:${process.env.PATH}`, HOME: temporary, LANG: "en_US.UTF-8", TEST_COMPOSE_LOG: log };
      if (acceptance) {
        env.COMPOSE_PROJECT_NAME = "acceptance-routing-test";
        env.LOCAL_COMPOSE_ENV_FILE = path.join(temporary, "acceptance.env");
        env.LOCAL_COMPOSE_OVERRIDE_FILE = path.join(root, "deploy/acceptance/failure-recovery.override.yaml");
      }
      const result = spawnSync("bash", [path.join(root, "scripts/initialize-local-iam-signing-key.sh")], { env, encoding: "utf8" });
      assert.equal(result.status, 17, result.stderr);
      const calls = (await readFile(log, "utf8")).trim().split("\n").map(JSON.parse);
      assert.equal(calls.length, 4);
      const expected = acceptance
        ? Array(4).fill("deploy/acceptance")
        : ["saas-forge-services/iam-service", "deploy/compose", "saas-forge-services/iam-service", "deploy/compose"];
      for (const [index, args] of calls.entries()) {
        assert.equal(args[args.indexOf("--project-directory") + 1], path.join(root, expected[index]));
        assert.equal(args[args.indexOf("--file") + 1], path.join(root, expected[index], "compose.yaml"));
        assert.equal(args.includes("--project-name"), acceptance);
      }
      assert.deepEqual(calls[1].slice(-4), ["up", "--detach", "--wait", "postgres"]);
      assert.deepEqual(calls[2].slice(-3), ["run", "--rm", "iam-migrate"]);
    } finally {
      await rm(temporary, { recursive: true, force: true });
    }
  });
}

test("Nacos readiness reports the last response code without configuration or credentials", async () => {
  const source = await readFile(path.join(root, "deploy/compose/nacos-init.sh"), "utf8");
  const functions = source.slice(source.indexOf("workload_config_is_readable()"), source.indexOf('wait_for_workload_config iam-service'));
  const script = `
set -eu
api=http://unused.invalid
NACOS_NAMESPACE=dev
login() { printf '%s' 'fixture-token'; }
curl() { printf '%s' "$TEST_NACOS_RESPONSE"; }
sleep() { :; }
${functions}
wait_for_workload_config gateway fixture-user fixture-password
`;
  const run = (response) => spawnSync("sh", ["-c", script], {
    encoding: "utf8",
    env: { ...process.env, TEST_NACOS_RESPONSE: JSON.stringify(response) },
  });
  const denied = run({ code: 403, message: "private-config-value" });
  assert.equal(denied.status, 1);
  assert.match(denied.stderr, /gateway.*最近响应码：403/u);
  assert.doesNotMatch(denied.stdout + denied.stderr, /fixture-token|fixture-password|private-config-value/u);
  const readable = run({ code: 0, data: { content: "spring: true" } });
  assert.equal(readable.status, 0, readable.stderr);
});
