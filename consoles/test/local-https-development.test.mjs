import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { request as requestHttps } from 'node:https';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  certificateCoversExpectedHosts,
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

test('creates one reusable local certificate for the Platform and API development hosts', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-'));
  const paths = developmentHttpsPaths(directory);
  t.after(() => rm(directory, { recursive: true, force: true }));

  assert.equal(await ensureCertificateMaterial(paths), 'created');
  assert.equal(await certificateCoversExpectedHosts(paths.serverCertificate), true);
  const firstCertificate = await readFile(paths.serverCertificate, 'utf8');

  assert.equal(await ensureCertificateMaterial(paths), 'reused');
  assert.equal(await readFile(paths.serverCertificate, 'utf8'), firstCertificate);
});

test('accepts only the two fixed local hostnames and preserves browser security headers verbatim', () => {
  assert.deepEqual(targetForHost('platform.saasforge.test'), {
    hostname: 'host.docker.internal',
    port: 5173,
  });
  assert.deepEqual(targetForHost('api.saasforge.test'), { hostname: 'gateway', port: 8080 });
  assert.equal(targetForHost('console.saasforge.test'), undefined);
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

test('relays the Platform WebSocket upgrade used by Vite HMR', async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-local-https-hmr-'));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const upstream = createServer();
  upstream.on('upgrade', (incoming, socket) => {
    assert.equal(incoming.headers.host, 'platform.saasforge.test');
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
      'platform.saasforge.test': { hostname: '127.0.0.1', port: upstream.address().port },
    },
  });
  await listen(edge);
  t.after(async () => {
    await Promise.all([close(edge), close(upstream)]);
    await rm(directory, { recursive: true, force: true });
  });

  await hmrUpgrade(edge.address().port, await readFile(paths.certificateAuthorityCertificate));
});

test('recognizes the idempotent local hosts entry and fixes Vite to the Edge-facing port', () => {
  assert.equal(
    hasExpectedHosts(
      '127.0.0.1 platform.saasforge.test api.saasforge.test # SaaS Forge local HTTPS\n',
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

test('requires an explicit operation and Platform target for lifecycle commands', () => {
  for (const operation of ['start', 'status', 'stop']) {
    assert.deepEqual(localHttpsDevelopmentCommand([operation, 'platform']), {
      command: operation,
      target: 'platform',
    });
  }
  assert.equal(localHttpsDevelopmentCommand(['start']), undefined);
  assert.equal(localHttpsDevelopmentCommand(['start', 'platform', 'extra']), undefined);
  assert.equal(localHttpsDevelopmentCommand(['start', 'tenant']), undefined);
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

function hmrUpgrade(port, certificateAuthority) {
  return new Promise((resolve, reject) => {
    const request_ = requestHttps({
      hostname: '127.0.0.1',
      port,
      path: '/',
      ca: certificateAuthority,
      servername: 'platform.saasforge.test',
      headers: {
        host: 'platform.saasforge.test',
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
