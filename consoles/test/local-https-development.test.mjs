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
  ensureCertificateMaterial,
  hasExpectedHosts,
  viteDevelopmentCommand,
} from '../../scripts/local-https-development.mjs';
import {
  copyOriginalHeaders,
  createEdgeServer,
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
  assert.deepEqual(command.args.slice(-7), [
    'run',
    'dev',
    '--',
    '--host',
    '0.0.0.0',
    '--port',
    '5173',
  ]);
  assert.equal(command.args.includes('install'), false);
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
        headers,
      },
      (response) => {
        response.resume();
        response.on('end', () => resolve(response.statusCode));
      },
    );
    request_.on('error', reject);
    request_.end();
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
