import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { verifyStaticRemoteRendering } from '../integration-test/static-remote-acceptance.mjs';

const evidence = { status: 'failed', stage: 'launch', requests: [], consoles: [], probes: [] };
const directory = new URL('../../.scratch/issue-156/', import.meta.url);
let browser;
const attack = createServer((_request, response) => {
  response
    .writeHead(200, { 'Content-Type': 'text/html' })
    .end('<!doctype html><title>Origin probe</title>');
});
const networkRecords = [];

async function observe(context) {
  const page = await context.newPage();
  page.setDefaultTimeout(10_000);
  const records = new Map();
  const record = (id) => {
    if (!records.has(id)) {
      const value = {};
      records.set(id, value);
      networkRecords.push(value);
    }
    return records.get(id);
  };
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  cdp.on('Network.requestWillBeSent', ({ requestId, request }) => {
    const url = new URL(request.url);
    if (url.hostname === 'remote.saasforge.test') {
      Object.assign(record(requestId), {
        path: url.pathname,
        origin: url.origin,
        method: request.method,
      });
    }
  });
  cdp.on('Network.requestWillBeSentExtraInfo', ({ requestId, headers }) => {
    const names = Object.keys(headers).map((name) => name.toLowerCase());
    // 只保留敏感头是否存在，不保存值、Cookie、Token 或任意原始 headers。
    record(requestId).credentials = names.filter((name) =>
      ['cookie', 'authorization', 'x-sf-csrf'].includes(name),
    );
  });
  cdp.on('Network.responseReceivedExtraInfo', ({ requestId, statusCode, headers }) => {
    const normalized = Object.fromEntries(
      Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]),
    );
    Object.assign(record(requestId), {
      status: statusCode,
      allowOrigin: normalized['access-control-allow-origin'] ?? null,
      allowCredentials: normalized['access-control-allow-credentials'] ?? null,
      contentType: normalized['content-type'] ?? null,
    });
  });
  cdp.on('Network.loadingFailed', ({ requestId, errorText, corsErrorStatus }) => {
    record(requestId).failure = /net::ERR_[A-Z_]+/u.exec(errorText)?.[0] ?? 'NETWORK_FAILURE';
    if (corsErrorStatus) record(requestId).corsFailure = corsErrorStatus.corsError;
  });
  const errors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push('console-error');
  });
  page.on('pageerror', () => errors.push('page-error'));
  const hmr = [];
  page.on('websocket', (socket) => {
    if (!socket.url().startsWith('wss://')) return;
    socket.on('framereceived', ({ payload }) => {
      if (String(payload) === '{"type":"connected"}') hmr.push(new URL(socket.url()).origin);
    });
  });
  return { page, errors, hmr, records };
}

try {
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  // 非敏感浏览器夹具使无 Cookie 断言不依赖“当前恰好没有 Remote Cookie”。
  await context.addCookies([
    {
      name: 'sf_static_probe',
      value: 'non-secret',
      domain: 'remote.saasforge.test',
      path: '/',
      secure: true,
      sameSite: 'Strict',
    },
  ]);
  // 与开发使用同样的浏览器信任库；不传 ignoreHTTPSErrors、解析覆盖或 TLS 绕过参数。
  const tenant = await observe(context);
  evidence.stage = 'tenant-rendering';
  await verifyStaticRemoteRendering(tenant.page);
  assert.deepEqual(tenant.errors, []);
  assert.ok(tenant.hmr.includes('wss://console.saasforge.test'));
  evidence.consoles.push({ host: 'console.saasforge.test', hmr: true, errors: 0 });

  evidence.stage = 'versions-and-404';
  const versions = await tenant.page.evaluate(async () => {
    const bodies = [];
    for (const version of ['v1', 'v2']) {
      const url = `https://remote.saasforge.test/static-acceptance/${version}/remote.js`;
      const first = await fetch(url, { credentials: 'omit', cache: 'no-store' });
      const second = await fetch(url, { credentials: 'omit', cache: 'no-store' });
      const text = await first.text();
      bodies.push({ status: first.status, stable: text === (await second.text()), text });
    }
    const missing = await fetch('https://remote.saasforge.test/static-acceptance/v1/missing.js', {
      credentials: 'omit',
    });
    return {
      distinct: bodies[0].text !== bodies[1].text,
      versions: bodies.map(({ status, stable }) => ({ status, stable })),
      missing: { status: missing.status, html: /<!doctype|<html/i.test(await missing.text()) },
    };
  });
  assert.deepEqual(versions, {
    distinct: true,
    versions: [
      { status: 200, stable: true },
      { status: 200, stable: true },
    ],
    missing: { status: 404, html: false },
  });
  evidence.versions = versions;

  evidence.stage = 'platform-hmr';
  const platform = await observe(context);
  await platform.page.goto('https://platform.saasforge.test/');
  await platform.page.waitForFunction(
    () => globalThis.document.querySelector('#root')?.textContent.length > 0,
  );
  await platform.page.waitForTimeout(500);
  assert.ok(platform.hmr.includes('wss://platform.saasforge.test'));
  assert.deepEqual(platform.errors, []);
  evidence.consoles.push({ host: 'platform.saasforge.test', hmr: true, errors: 0 });

  evidence.stage = 'api-tls';
  const api = await context.newPage();
  const apiResponse = await api.goto('https://api.saasforge.test/.well-known/jwks.json');
  assert.equal(apiResponse.status(), 200);
  evidence.api = { path: '/.well-known/jwks.json', status: 200 };
  await api.close();

  evidence.stage = 'cors-negative';
  await new Promise((resolve) => attack.listen(0, '127.0.0.1', resolve));
  const illegal = await observe(context);
  await illegal.page.goto(`http://localhost:${attack.address().port}/`);
  const opaque = await observe(context);
  await opaque.page.goto('data:text/html,<!doctype html><title>Opaque Origin probe</title>');
  for (const [name, probe] of [
    ['platform', platform],
    ['illegal', illegal],
    ['null', opaque],
  ]) {
    const denied = await probe.page.evaluate(async (name) => {
      try {
        await fetch(`https://remote.saasforge.test/static-acceptance/v1/remote.js?probe=${name}`, {
          credentials: 'omit',
          cache: 'no-store',
        });
        return false;
      } catch {
        return true;
      }
    }, name);
    assert.equal(denied, true);
    // ExtraInfo 保留 CORS 阻止读取时浏览器确实收到的响应，不能只凭 fetch 抛错通过。
    await probe.page.waitForTimeout(200);
    const responses = [...probe.records.values()].filter((item) => item.path && item.status);
    assert.ok(responses.length > 0, `${name}: no direct fourth-domain response evidence`);
    assert.ok(
      responses.every(
        (item) =>
          item.status === 200 && item.allowOrigin === null && item.allowCredentials === null,
      ),
    );
    assert.ok(probe.errors.includes('console-error'));
    assert.ok(!probe.errors.includes('page-error'));
    evidence.probes.push({
      origin: name,
      rejected: true,
      responseStatuses: responses.map((item) => item.status),
      expectedConsoleErrors: probe.errors.length,
    });
  }
  evidence.stage = 'network-evidence';
  const remote = networkRecords.filter((item) => item.path);
  assert.ok(remote.length >= 6);
  assert.ok(
    remote.every((item) => Array.isArray(item.credentials) && item.credentials.length === 0),
  );
  assert.ok(remote.every((item) => item.allowCredentials === null));
  for (const version of ['v1', 'v2']) {
    for (const file of ['remote.js', 'styles.css', 'image.svg']) {
      assert.ok(
        remote.some(
          (item) =>
            item.path === `/static-acceptance/${version}/${file}` &&
            item.status === 200 &&
            item.allowOrigin === 'https://console.saasforge.test',
        ),
      );
    }
  }
  evidence.status = 'passed';
  evidence.stage = 'complete';
  console.log(
    'PASS: four-domain trusted TLS, Remote rendering, credential-free CORS and Vite HMR connections',
  );
} catch {
  console.error(
    `BLOCKED/FAILED: ${evidence.stage}; see .scratch/issue-156/local-static-remote.json and run local HTTPS doctor`,
  );
  process.exitCode = 1;
} finally {
  evidence.requests = networkRecords.filter((item) => item.path);
  await mkdir(directory, { recursive: true });
  await writeFile(
    new URL('local-static-remote.json', directory),
    `${JSON.stringify(evidence, null, 2)}\n`,
  );
  await browser?.close();
  if (attack.listening) await new Promise((resolve) => attack.close(resolve));
}
