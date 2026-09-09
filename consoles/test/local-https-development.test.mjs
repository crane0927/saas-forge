import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { request as requestHttps } from 'node:https';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  certificateCoversExpectedHosts,
  developmentHosts,
  developmentHttpsPaths,
  doctorToolchain,
  ensureCertificateMaterial,
  hasExpectedHosts,
  localHttpsDevelopmentCommand,
  viteDevelopmentCommand,
} from '../../scripts/local-https-development.mjs';
import {
  copyOriginalHeaders,
  createEdgeServer,
  parseApiTarget,
  targetForHost,
} from '../../deploy/compose/local-https-development/edge.mjs';

test('creates one reusable local certificate for all four development hosts', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-'));
  const paths = developmentHttpsPaths(directory);
  t.after(() => rm(directory, { recursive: true, force: true }));

  assert.equal(await ensureCertificateMaterial(paths), 'created');
  assert.equal(await certificateCoversExpectedHosts(paths.serverCertificate), true);
  const firstCertificate = await readFile(paths.serverCertificate, 'utf8');
  const { X509Certificate } = await import('node:crypto');
  assert.equal(
    new X509Certificate(firstCertificate).checkHost('remote.saasforge.test'),
    'remote.saasforge.test',
  );

  assert.equal(await ensureCertificateMaterial(paths), 'reused');
  assert.equal(await readFile(paths.serverCertificate, 'utf8'), firstCertificate);
});

test('keeps the three existing proxy targets and preserves browser security headers verbatim', () => {
  assert.deepEqual(targetForHost('platform.saasforge.test'), {
    hostname: 'host.docker.internal',
    port: 5173,
  });
  assert.deepEqual(targetForHost('api.saasforge.test'), { hostname: 'gateway', port: 8080 });
  assert.deepEqual(targetForHost('console.saasforge.test'), {
    hostname: 'host.docker.internal',
    port: 5174,
  });
  assert.equal(targetForHost('unknown.saasforge.test'), undefined);
  assert.equal(targetForHost('platform.saasforge.test:443'), undefined);

  const headers = [
    'Origin',
    'https://platform.saasforge.test',
    'Cookie',
    '__Host-sf_platform_refresh=opaque',
    'Sec-Fetch-Site',
    'same-site',
    'Authorization',
    'Bearer opaque',
  ];
  assert.deepEqual(copyOriginalHeaders(headers), headers);
});

test('forwards browser security headers without Edge synthesis or rewriting', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-edge-'));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const seen = new Map();
  const upstream = createServer((incoming, outgoing) => {
    for (let index = 0; index < incoming.rawHeaders.length; index += 2) {
      seen.set(incoming.rawHeaders[index].toLowerCase(), incoming.rawHeaders[index + 1]);
    }
    outgoing.writeHead(204).end();
  });
  await listen(upstream);
  const upstreamPort = upstream.address().port;
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
    targets: { 'api.saasforge.test': { hostname: '127.0.0.1', port: upstreamPort } },
  });
  await listen(edge);
  t.after(async () => {
    await Promise.all([close(edge), close(upstream)]);
    await rm(directory, { recursive: true, force: true });
  });

  const status = await edgeRequest(
    edge.address().port,
    await readFile(paths.certificateAuthorityCertificate),
    {
      host: 'api.saasforge.test',
      origin: 'https://platform.saasforge.test',
      cookie: '__Host-sf_platform_refresh=opaque',
      'sec-fetch-site': 'same-site',
      authorization: 'Bearer opaque',
    },
  );
  assert.equal(status, 204);
  assert.equal(seen.get('origin'), 'https://platform.saasforge.test');
  assert.equal(seen.get('cookie'), '__Host-sf_platform_refresh=opaque');
  assert.equal(seen.get('sec-fetch-site'), 'same-site');
  assert.equal(seen.get('authorization'), 'Bearer opaque');
});

test('accepts only the fixed local Gateway override target', () => {
  assert.deepEqual(
    parseApiTarget(JSON.stringify({ hostname: 'host.docker.internal', port: 8080 })),
    { hostname: 'host.docker.internal', port: 8080 },
  );
  assert.deepEqual(parseApiTarget(JSON.stringify({ hostname: 'gateway', port: 8080 })), {
    hostname: 'gateway',
    port: 8080,
  });
  assert.equal(
    parseApiTarget(JSON.stringify({ hostname: 'untrusted.example', port: 8080 })),
    undefined,
  );
  assert.equal(parseApiTarget('{'), undefined);
});

for (const host of ['platform.saasforge.test', 'console.saasforge.test']) {
  test(`relays ${host} WebSocket upgrade used by Vite HMR`, async (t) => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-hmr-'));
    const paths = developmentHttpsPaths(directory);
    await ensureCertificateMaterial(paths);
    const upstream = createServer();
    upstream.on('upgrade', (incoming, socket) => {
      assert.equal(incoming.headers.host, host);
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n',
      );
      socket.on('data', (data) => socket.write(data));
      socket.on('end', () => socket.end());
    });
    await listen(upstream);
    const edge = createEdgeServer({
      certificate: await readFile(paths.serverCertificate),
      key: await readFile(paths.serverKey),
      targets: {
        [host]: { hostname: '127.0.0.1', port: upstream.address().port },
      },
    });
    await listen(edge);
    t.after(async () => {
      await Promise.all([close(edge), close(upstream)]);
      await rm(directory, { recursive: true, force: true });
    });

    await hmrUpgrade(
      edge.address().port,
      await readFile(paths.certificateAuthorityCertificate),
      host,
    );
  });
}

test('recognizes the idempotent local hosts entry and fixes Vite to the Edge-facing port', () => {
  assert.equal(
    hasExpectedHosts(
      '127.0.0.1 platform.saasforge.test console.saasforge.test api.saasforge.test remote.saasforge.test # SaaS Forge local HTTPS\n',
    ),
    true,
  );
  assert.equal(hasExpectedHosts('127.0.0.1 platform.saasforge.test\n'), false);

  const command = viteDevelopmentCommand('/workspace/consoles');
  assert.deepEqual(command.args.slice(-8), [
    'run',
    'dev',
    '--',
    '--host',
    '127.0.0.1',
    '--port',
    '5173',
    '--strictPort',
  ]);
  assert.equal(command.args.includes('install'), false);
});

test('keeps the managed Platform PID and log separate from legacy diagnostics', () => {
  const paths = developmentHttpsPaths('/workspace');
  assert.match(paths.platformPid, /\/platform-vite\.pid$/u);
  assert.match(paths.platformLog, /\/platform-vite\.log$/u);
  assert.match(paths.legacyVitePid, /\/vite\.pid$/u);
  assert.match(paths.legacyViteLog, /\/vite\.log$/u);
  assert.notEqual(paths.platformPid, paths.legacyVitePid);
  assert.notEqual(paths.platformLog, paths.legacyViteLog);
});

test('requires an explicit operation and fixed Console target for lifecycle commands', () => {
  for (const operation of ['start', 'status', 'stop']) {
    assert.deepEqual(localHttpsDevelopmentCommand([operation, 'tenant']), {
      command: operation,
      target: 'tenant',
    });
    assert.deepEqual(localHttpsDevelopmentCommand([operation, 'platform']), {
      command: operation,
      target: 'platform',
    });
  }
  assert.equal(localHttpsDevelopmentCommand(['start']), undefined);
  assert.equal(localHttpsDevelopmentCommand(['start', 'platform', 'extra']), undefined);
  for (const operation of ['start', 'status', 'stop']) {
    assert.deepEqual(localHttpsDevelopmentCommand([operation, 'all']), {
      command: operation,
      target: 'all',
    });
    assert.equal(localHttpsDevelopmentCommand([operation, 'all', 'extra']), undefined);
  }
});

test('pins the Vite dependency layout and rejects an incompatible installed workspace', async () => {
  const workspaceConfiguration = await readFile(
    new URL('../pnpm-workspace.yaml', import.meta.url),
    'utf8',
  );
  assert.match(workspaceConfiguration, /^enableGlobalVirtualStore: false$/mu);

  const calls = [];
  const result = doctorToolchain('/workspace', (command, args, options) => {
    calls.push({ command, args, options });
    if (command === 'test') return { status: 0, stdout: '' };
    if (args.includes('vite')) return { status: 1, stdout: '' };
    if (args.at(-1) === '--version' && args.includes('node')) {
      return { status: 0, stdout: 'v24.14.1\n' };
    }
    if (args.at(-1) === '--version' && args.includes('pnpm')) {
      return { status: 0, stdout: '11.22.0\n' };
    }
    return { status: 1, stdout: '' };
  });

  assert.equal(result.code, 'TOOLCHAIN_INVALID');
  assert.equal(
    calls.some(
      ({ args, options }) =>
        args.includes('exec') &&
        args.includes('vite') &&
        args.at(-1) === '--version' &&
        options.env.PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE === 'false',
    ),
    true,
  );
});

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
}

function close(server) {
  return new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}

function edgeRequest(port, certificateAuthority, headers) {
  return new Promise((resolve, reject) => {
    const request_ = requestHttps(
      {
        hostname: '127.0.0.1',
        port,
        method: 'POST',
        path: '/api/v1/auth/refresh',
        ca: certificateAuthority,
        servername: 'api.saasforge.test',
        headers: { ...headers, 'content-type': 'application/json' },
      },
      (response) => {
        response.resume();
        response.on('end', () => resolve(response.statusCode));
      },
    );
    request_.on('error', reject);
    request_.end(JSON.stringify({ sessionSlot: 'PLATFORM' }));
  });
}

function hmrUpgrade(port, certificateAuthority, host) {
  return new Promise((resolve, reject) => {
    const request_ = requestHttps({
      hostname: '127.0.0.1',
      port,
      path: '/',
      ca: certificateAuthority,
      servername: host,
      headers: {
        host,
        connection: 'Upgrade',
        upgrade: 'websocket',
      },
    });
    request_.on('upgrade', (response, socket) => {
      assert.equal(response.statusCode, 101);
      socket.write('hmr-ping');
      socket.once('data', (data) => {
        assert.equal(data.toString(), 'hmr-ping');
        socket.end();
        resolve();
      });
      socket.once('error', reject);
    });
    request_.on('error', reject);
    request_.end();
  });
}

test('upgrades a three-Host leaf with the existing CA and then remains idempotent', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-upgrade-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const authority = await readFile(paths.certificateAuthorityCertificate);
  const authorityKey = await readFile(paths.certificateAuthorityKey);
  await writeFile(
    paths.serverExtensions,
    'subjectAltName=DNS:platform.saasforge.test,DNS:console.saasforge.test,DNS:api.saasforge.test\n',
  );
  const result = spawnSync('openssl', [
    'x509',
    '-req',
    '-in',
    paths.serverRequest,
    '-CA',
    paths.certificateAuthorityCertificate,
    '-CAkey',
    paths.certificateAuthorityKey,
    '-CAcreateserial',
    '-days',
    '397',
    '-extfile',
    paths.serverExtensions,
    '-out',
    paths.serverCertificate,
  ]);
  assert.equal(result.status, 0);
  assert.equal(await certificateCoversExpectedHosts(paths.serverCertificate), false);
  assert.equal(await ensureCertificateMaterial(paths), 'created');
  assert.deepEqual(await readFile(paths.certificateAuthorityCertificate), authority);
  assert.deepEqual(await readFile(paths.certificateAuthorityKey), authorityKey);
  assert.equal(await certificateCoversExpectedHosts(paths.serverCertificate), true);
  assert.equal(await ensureCertificateMaterial(paths), 'reused');
  assert.deepEqual(developmentHosts, [
    'platform.saasforge.test',
    'console.saasforge.test',
    'api.saasforge.test',
    'remote.saasforge.test',
  ]);
});

test('Tenant uses a separate command, PID and log and requires all four hosts', () => {
  const command = viteDevelopmentCommand('/workspace/consoles', 'tenant');
  assert.ok(command.args.includes('@saas-forge/tenant-console-shell'));
  assert.deepEqual(command.args.slice(-5), [
    '--host',
    '127.0.0.1',
    '--port',
    '5174',
    '--strictPort',
  ]);
  const paths = developmentHttpsPaths('/workspace');
  assert.ok(paths.tenantPid.endsWith('/tenant-vite.pid'));
  assert.ok(paths.tenantLog.endsWith('/tenant-vite.log'));
  assert.notEqual(paths.tenantPid, paths.platformPid);
  assert.notEqual(paths.tenantLog, paths.platformLog);
  assert.equal(hasExpectedHosts('127.0.0.1 platform.saasforge.test api.saasforge.test\n'), false);
  assert.equal(
    hasExpectedHosts(
      '127.0.0.1 platform.saasforge.test api.saasforge.test\n127.0.0.1 console.saasforge.test remote.saasforge.test\n',
    ),
    true,
  );
  assert.equal(
    hasExpectedHosts(
      '127.0.0.1 platform.saasforge.test api.saasforge.test\n192.168.1.1 console.saasforge.test\n',
    ),
    false,
  );
});

test('loads both actual Vite configs with loopback ports, exact Hosts and controlled WSS HMR', async () => {
  const { loadConfigFromFile } = await import('vite');
  for (const [directory, port, host] of [
    ['platform-console', 5173, 'platform.saasforge.test'],
    ['tenant-console-shell', 5174, 'console.saasforge.test'],
  ]) {
    const loaded = await loadConfigFromFile(
      { command: 'serve', mode: 'development' },
      new URL('../' + directory + '/vite.config.ts', import.meta.url).pathname,
    );
    assert.equal(loaded.config.server.host, '127.0.0.1');
    assert.equal(loaded.config.server.port, port);
    assert.equal(loaded.config.server.strictPort, true);
    assert.deepEqual(loaded.config.server.allowedHosts, [host]);
    assert.deepEqual(loaded.config.server.hmr, { protocol: 'wss', host, clientPort: 443 });
  }
});

test('routes both Console HTTPS Hosts independently and rejects unknown Hosts', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-two-console-edge-'));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const platform = createServer((req, res) => res.writeHead(201).end());
  const tenant = createServer((req, res) => res.writeHead(202).end());
  await Promise.all([listen(platform), listen(tenant)]);
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
    targets: {
      'platform.saasforge.test': { hostname: '127.0.0.1', port: platform.address().port },
      'console.saasforge.test': { hostname: '127.0.0.1', port: tenant.address().port },
    },
  });
  await listen(edge);
  t.after(async () => {
    await Promise.all([close(edge), close(platform), close(tenant)]);
    await rm(directory, { recursive: true, force: true });
  });
  const ca = await readFile(paths.certificateAuthorityCertificate);
  for (const [host, expected] of [
    ['platform.saasforge.test', 201],
    ['console.saasforge.test', 202],
    ['unknown.saasforge.test', 421],
    ['console.saasforge.test:443', 421],
  ]) {
    assert.equal(await edgeRequest(edge.address().port, ca, { host }), expected);
  }
});

test('hosts upgrade requires explicit consent, is idempotent and refuses noninteractive authorization', async () => {
  const { installHosts, confirm } = await import('../../scripts/local-https-development.mjs');
  let content = '127.0.0.1 platform.saasforge.test api.saasforge.test\n';
  let authorized = false;
  let writes = 0;
  const system = {
    readHosts: async () => content,
    authorize: async (question, expected) => {
      assert.equal(expected, 'HOSTS');
      assert.ok(question.includes('四个'));
      authorized = true;
    },
    appendHosts: async (entry) => {
      assert.equal(authorized, true);
      content += entry;
      writes++;
    },
  };
  await assert.rejects(
    () =>
      installHosts({
        ...system,
        authorize: async () => {
          throw new Error('declined');
        },
      }),
    /declined/u,
  );
  assert.equal(writes, 0);
  await installHosts(system);
  assert.equal(hasExpectedHosts(content), true);
  await installHosts({
    ...system,
    authorize: async () => {
      throw new Error('must skip repeated authorization');
    },
  });
  assert.equal(writes, 1);
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    await assert.rejects(() => confirm('test', 'HOSTS'), /非交互终端/u);
  }
});
