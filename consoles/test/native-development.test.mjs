import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { createLogger, createServer } from 'vite';

const consoles = fileURLToPath(new URL('..', import.meta.url));

for (const [application, origin] of [
  ['platform-console', 'https://platform.saasforge.test/'],
  ['tenant-console-shell', 'https://console.saasforge.test/'],
]) {
  test(`${application} startup directs the browser to its controlled HTTPS Origin`, async (t) => {
    const messages = [];
    const logger = createLogger();
    logger.info = (message) => messages.push(message);
    const server = await createServer({
      root: path.join(consoles, application),
      customLogger: logger,
      server: { port: 0, strictPort: false },
    });
    t.after(() => server.close());
    await server.listen();
    server.printUrls();
    const output = messages.join('\n');
    assert.ok(output.includes(`浏览器入口: ${origin}`), output);
    assert.match(output, /内部监听（非浏览器入口）: 127\.0\.0\.1:\d+/u);
    assert.doesNotMatch(output, /http:\/\/127\.0\.0\.1/u);
  });
}

for (const application of ['platform-console', 'tenant-console-shell']) {
  test(`${application} dev explains how to prepare a missing API Client`, async (t) => {
    const repository = await mkdtemp(path.join(os.tmpdir(), 'sf-native-dev-'));
    t.after(() => rm(repository, { recursive: true, force: true }));
    const root = path.join(repository, 'consoles');
    const directory = path.join(root, application);
    await mkdir(directory, { recursive: true });
    await cp(path.join(consoles, 'package.json'), path.join(root, 'package.json'));
    await cp(
      path.join(consoles, application, 'package.json'),
      path.join(directory, 'package.json'),
    );
    await cp(path.join(consoles, 'scripts'), path.join(root, 'scripts'), { recursive: true });
    await symlink(path.join(consoles, 'node_modules'), path.join(root, 'node_modules'));
    await symlink(
      path.join(consoles, application, 'node_modules'),
      path.join(directory, 'node_modules'),
    );
    const result = spawnSync('pnpm', ['run', 'dev'], {
      cwd: directory,
      encoding: 'utf8',
      timeout: 10_000,
      // 临时夹具复用已安装工具，不安装夹具声明的 workspace 依赖。
      env: {
        ...process.env,
        COREPACK_ENABLE_NETWORK: '0',
        pnpm_config_verify_deps_before_run: 'false',
      },
    });
    assert.equal(result.error, undefined);
    assert.notEqual(result.status, 0);
    assert.match(result.stdout + result.stderr, /pnpm --dir consoles run generate:api/u);
    assert.doesNotMatch(result.stdout + result.stderr, /VITE v/u);

    // 复用正式生成物；隔离副本允许验证缺文件和契约变化，不破坏开发工作区。
    for (const file of ['pom.xml', 'contracts/openapi/pom.xml', 'contracts/openapi/v1.yaml']) {
      await mkdir(path.dirname(path.join(repository, file)), { recursive: true });
      await cp(path.join(consoles, '..', file), path.join(repository, file));
    }
    const generated = path.join(root, 'shared/api-client/.generated');
    await cp(path.join(consoles, 'shared/api-client/.generated'), generated, { recursive: true });
    // Maven 是外部工具边界；夹具保留正式 Client，仅模拟该工具成功/失败。
    await writeFile(path.join(repository, 'mvnw'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
    const prepare = () =>
      spawnSync(process.execPath, [path.join(root, 'scripts/check-api-client.mjs'), '--generate'], {
        encoding: 'utf8',
        timeout: 10_000,
      });
    assert.equal(prepare().status, 0);
    const run = () =>
      spawnSync('pnpm', ['run', 'dev', '--help'], {
        cwd: directory,
        encoding: 'utf8',
        timeout: 10_000,
        env: {
          ...process.env,
          COREPACK_ENABLE_NETWORK: '0',
          pnpm_config_verify_deps_before_run: 'false',
        },
      });
    const ready = run();
    assert.equal(ready.status, 0, ready.stdout + ready.stderr);
    assert.match(ready.stdout + ready.stderr, /vite\//u);
    assert.doesNotMatch(ready.stdout + ready.stderr, /Reactor Build|mvnw/u);

    await writeFile(path.join(repository, 'contracts/openapi/v1.yaml'), '\n# contract changed\n', {
      flag: 'a',
    });
    assert.notEqual(run().status, 0);
    await cp(
      path.join(consoles, '../contracts/openapi/v1.yaml'),
      path.join(repository, 'contracts/openapi/v1.yaml'),
    );
    await writeFile(path.join(repository, 'mvnw'), '#!/bin/sh\nexit 1\n');
    assert.notEqual(prepare().status, 0);
    assert.notEqual(run().status, 0, 'failed generation must invalidate the previous preparation');
    await writeFile(path.join(repository, 'mvnw'), '#!/bin/sh\nexit 0\n');
    assert.equal(prepare().status, 0);
    await rm(path.join(generated, 'runtime.ts'));
    assert.notEqual(run().status, 0);
  });
}
