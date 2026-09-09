import assert from 'node:assert/strict';
import { request } from 'node:https';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { createEdgeServer } from '../../deploy/compose/local-https-development/edge.mjs';
import {
  developmentHttpsPaths,
  ensureCertificateMaterial,
} from '../../scripts/local-https-development.mjs';

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'sf-remote-edge-'));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
  });
  await new Promise((resolve) => edge.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise((resolve) => edge.close(resolve));
    await rm(directory, { recursive: true, force: true });
  });
  const ca = await readFile(paths.certificateAuthorityCertificate);
  return (pathname, origin = 'https://console.saasforge.test', method = 'GET') =>
    new Promise((resolve, reject) => {
      const req = request(
        {
          hostname: '127.0.0.1',
          port: edge.address().port,
          servername: 'remote.saasforge.test',
          ca,
          path: pathname,
          method,
          headers: { host: 'remote.saasforge.test', ...(origin === undefined ? {} : { origin }) },
        },
        (response) => {
          const chunks = [];
          response.on('data', (chunk) => chunks.push(chunk));
          response.on('end', () =>
            resolve({
              status: response.statusCode,
              headers: response.headers,
              body: Buffer.concat(chunks),
            }),
          );
        },
      );
      req.on('error', reject);
      req.end();
    });
}

test('both version paths deliver stable, distinct modules, CSS and images without HTML fallback', async (t) => {
  const get = await fixture(t);
  const modules = [];
  for (const version of ['v1', 'v2']) {
    for (const [file, mime] of [
      ['remote.js', 'text/javascript'],
      ['styles.css', 'text/css'],
      ['image.svg', 'image/svg+xml'],
    ]) {
      const url = `/static-acceptance/${version}/${file}`;
      const first = await get(url);
      assert.equal(first.status, 200);
      assert.ok(first.headers['content-type'].startsWith(mime));
      assert.deepEqual((await get(url)).body, first.body);
      assert.equal(first.headers['cache-control'], 'public, max-age=31536000, immutable');
      if (file === 'remote.js') modules.push(first.body.toString());
    }
  }
  assert.notEqual(modules[0], modules[1]);
  const missing = await get('/static-acceptance/v1/missing.js');
  assert.equal(missing.status, 404);
  assert.doesNotMatch(missing.body.toString(), /<!doctype|<html/iu);
});

test('Remote grants only exact credential-free Tenant CORS and only read methods', async (t) => {
  const get = await fixture(t);
  for (const origin of [
    'https://platform.saasforge.test',
    'https://evil.saasforge.test',
    'https://console.saasforge.test.evil.example',
    'null',
  ]) {
    const response = await get('/static-acceptance/v1/remote.js', origin);
    assert.equal(response.status, 200);
    assert.equal(response.headers['access-control-allow-origin'], undefined);
    assert.equal(response.headers['access-control-allow-credentials'], undefined);
    assert.equal(response.headers.vary, 'Origin');
  }
  const rejected = await get(
    '/static-acceptance/v1/remote.js',
    'https://console.saasforge.test',
    'POST',
  );
  assert.equal(rejected.status, 405);
  const head = await get(
    '/static-acceptance/v1/remote.js',
    'https://console.saasforge.test',
    'HEAD',
  );
  assert.equal(head.status, 200);
  assert.equal(head.body.length, 0);
});

test('Tenant can read a built Remote ES module through trusted fourth-domain HTTPS', async (t) => {
  const get = await fixture(t);
  const response = await get('/static-acceptance/v1/remote.js');
  assert.equal(response.status, 200);
  assert.match(response.headers['content-type'], /javascript/u);
  assert.equal(response.headers['access-control-allow-origin'], 'https://console.saasforge.test');
  assert.equal(response.headers['access-control-allow-credentials'], undefined);
  assert.match(response.body.toString(), /export/u);
});
