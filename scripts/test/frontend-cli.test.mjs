import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdtemp, mkdir, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

test("status all CLI always prints both endpoints and Edge, and unavailable Docker fails safely", async (t) => {
  const directory = await realpath(
    await mkdtemp(path.join(os.tmpdir(), "sf-frontend-cli-")),
  );
  t.after(() => rm(directory, { recursive: true, force: true }));
  await mkdir(path.join(directory, "scripts"));
  await mkdir(path.join(directory, "deploy/compose"), { recursive: true });
  await mkdir(path.join(directory, "bin"));
  for (const name of [
    "local-https-development.mjs",
    "frontend-lifecycle.mjs",
  ]) {
    await cp(
      new URL(`../${name}`, import.meta.url),
      path.join(directory, "scripts", name),
    );
  }
  await writeFile(path.join(directory, "bin/lsof"), "#!/bin/sh\nexit 1\n", {
    mode: 0o700,
  });
  for (const dockerExitCode of [0, 1]) {
    await writeFile(
      path.join(directory, "bin/docker"),
      `#!/bin/sh\nexit ${dockerExitCode}\n`,
      { mode: 0o700 },
    );
    const result = spawnSync(
      process.execPath,
      [
        path.join(directory, "scripts/local-https-development.mjs"),
        "status",
        "all",
      ],
      {
        env: { PATH: `${directory}/bin:${process.env.PATH}` },
        encoding: "utf8",
      },
    );
    assert.equal(result.status, dockerExitCode, result.stderr);
    assert.equal(
      result.stdout,
      "PLATFORM: STOPPED | 127.0.0.1:5173 | https://platform.saasforge.test | HTTPS NOT_READY\n" +
        "TENANT: STOPPED | 127.0.0.1:5174 | https://console.saasforge.test | HTTPS NOT_READY\n" +
        `EDGE: ${dockerExitCode === 0 ? "STOPPED" : "UNAVAILABLE"} | 127.0.0.1:443 | NOT_READY\n`,
    );
    assert.doesNotMatch(
      result.stdout + result.stderr,
      /Cookie|Token|PRIVATE KEY|Client Secret/u,
    );
  }
});
