import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';
const api = `https://api.${rootDomain}`;
const names = ['__Host-sf_platform_refresh', '__Host-sf_tenant_refresh'];

/** 匿名页面启动时的正常恢复失败，由各验收入口共用精确分类。 */
export function isAnonymousRefreshError(message) {
  return (
    message.location().url === `${api}/api/v1/auth/refresh` &&
    /^Failed to load resource: the server responded with a status of 401/.test(message.text())
  );
}

const list = (value) =>
  (value ?? '')
    .toLowerCase()
    .split(/[,\n]/)
    .map((part) => part.trim())
    .filter(Boolean)
    .sort();

/** WebKit/Firefox 的可读响应使用公开网络 API；CORS 隐藏响应由关联的真实 Edge 日志补齐。 */
async function observePortableSecurity(page, records) {
  const pending = new Set();
  const observe = async (response) => {
    const request = response.request();
    const url = new URL(request.url());
    if (
      !['api', 'platform', 'console', 'remote'].some(
        (host) => url.hostname === `${host}.${rootDomain}`,
      )
    )
      return;
    const h = await request.allHeaders();
    const r = await response.allHeaders();
    const cookieHeaders = [];
    for (const header of await response.headersArray()) {
      if (header.name.toLowerCase() !== 'set-cookie') continue;
      const previous = cookieHeaders.at(-1);
      // WebKit 的公开头数组会在 Expires 的逗号处拆开；只重连 HTTP 日期片段。
      if (
        previous &&
        /;\s*Expires=(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)$/i.test(previous.value) &&
        /^\d{2} [A-Za-z]{3} \d{4} \d{2}:\d{2}:\d{2} GMT(?:;|$)/.test(header.value)
      )
        previous.value += `, ${header.value}`;
      else cookieHeaders.push({ ...header });
    }
    records.push({
      host: url.hostname,
      path: url.pathname,
      method: request.method(),
      probe: url.searchParams.get('sessionProbe'),
      origin: h.origin ?? null,
      fetchSite: h['sec-fetch-site'] ?? null,
      refreshCookies: names.filter((name) =>
        (h.cookie ?? '').split(';').some((part) => part.trim().startsWith(`${name}=`)),
      ),
      authorization: Boolean(h.authorization),
      status: response.status(),
      allowOrigin: r['access-control-allow-origin'] ?? null,
      allowCredentials: r['access-control-allow-credentials'] ?? null,
      allowMethods: list(r['access-control-allow-methods']),
      allowHeaders: list(r['access-control-allow-headers']),
      requestedHeaders: list(h['access-control-request-headers']),
      exposeHeaders: list(r['access-control-expose-headers']),
      maxAge: r['access-control-max-age'] ?? null,
      vary: list(r.vary),
      cookies: cookieHeaders
        .filter(({ value }) => names.some((name) => value.startsWith(`${name}=`)))
        .map(({ value }) => ({
          name: value.slice(0, value.indexOf('=')),
          secure: /;\s*Secure(?:;|$)/i.test(value),
          httpOnly: /;\s*HttpOnly(?:;|$)/i.test(value),
          strict: /;\s*SameSite=Strict(?:;|$)/i.test(value),
          rootPath: /;\s*Path=\/(?:;|$)/i.test(value),
          hostOnly: !/;\s*Domain=/i.test(value),
          clear: /;\s*Max-Age=0(?:;|$)/i.test(value),
        })),
    });
  };
  const errors = [];
  const listener = (response) => {
    const task = observe(response).catch(() => errors.push('network observation failed'));
    pending.add(task);
    void task.finally(() => pending.delete(task));
  };
  page.on('response', listener);
  return {
    flush: async () => {
      await Promise.all(pending);
      assert.deepEqual(errors, []);
    },
    detach: async () => {
      page.off('response', listener);
      await Promise.all(pending);
      assert.deepEqual(errors, []);
    },
  };
}

export function edgeProbeRecords(probe, event = 'acceptance-session-security') {
  const container = process.env.SF_SECURITY_EDGE_CONTAINER;
  assert.ok(
    container,
    'BLOCKED: SF_SECURITY_EDGE_CONTAINER is required for direct CORS refusal evidence',
  );
  let logs;
  try {
    logs = execFileSync('docker', ['logs', container], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      maxBuffer: 16 * 1024 * 1024,
    });
  } catch {
    assert.fail('BLOCKED: cannot read the acceptance TLS Edge evidence');
  }
  return logs.split('\n').flatMap((line) => {
    try {
      const value = JSON.parse(line);
      return value.event === event && value.probe === probe ? [value] : [];
    } catch {
      return [];
    }
  });
}

/** Chromium ExtraInfo 是 CORS 隐藏响应时的直接网络证据；只保留允许的非敏感字段。 */
export async function observeBrowserSecurity(context, page, records) {
  if (context.browser().browserType().name() !== 'chromium')
    return observePortableSecurity(page, records);
  const cdp = await context.newCDPSession(page);
  const requests = new Map();
  const record = (id) => {
    if (!requests.has(id)) requests.set(id, {});
    return requests.get(id);
  };
  const normalize = (headers) =>
    Object.fromEntries(Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]));
  cdp.on('Network.requestWillBeSent', ({ requestId, request }) => {
    const url = new URL(request.url);
    if (
      !['api', 'platform', 'console', 'remote'].some(
        (host) => url.hostname === `${host}.${rootDomain}`,
      )
    )
      return;
    const value = record(requestId);
    if (!value.path) records.push(value);
    Object.assign(value, {
      host: url.hostname,
      path: url.pathname,
      method: request.method,
      probe: url.searchParams.get('sessionProbe'),
    });
  });
  cdp.on('Network.requestWillBeSentExtraInfo', ({ requestId, headers }) => {
    const h = normalize(headers);
    Object.assign(record(requestId), {
      origin: h.origin ?? null,
      fetchSite: h['sec-fetch-site'] ?? null,
      refreshCookies: names.filter((name) =>
        (h.cookie ?? '').split(';').some((part) => part.trim().startsWith(`${name}=`)),
      ),
      authorization: Boolean(h.authorization),
      csrf: h['x-sf-csrf'] === '1' ? 'valid' : h['x-sf-csrf'] === undefined ? 'missing' : 'invalid',
      requestedMethod: h['access-control-request-method'] ?? null,
      requestedHeaders: list(h['access-control-request-headers']),
    });
  });
  cdp.on('Network.responseReceivedExtraInfo', ({ requestId, statusCode, headers }) => {
    const h = normalize(headers);
    Object.assign(record(requestId), {
      status: statusCode,
      allowOrigin: h['access-control-allow-origin'] ?? null,
      allowCredentials: h['access-control-allow-credentials'] ?? null,
      allowMethods: list(h['access-control-allow-methods']),
      allowHeaders: list(h['access-control-allow-headers']),
      exposeHeaders: list(h['access-control-expose-headers']),
      maxAge: h['access-control-max-age'] ?? null,
      vary: list(h.vary),
      cookies: (h['set-cookie'] ?? '')
        .split('\n')
        .filter((value) => names.some((name) => value.startsWith(`${name}=`)))
        .map((value) => ({
          name: value.slice(0, value.indexOf('=')),
          secure: /;\s*Secure(?:;|$)/i.test(value),
          httpOnly: /;\s*HttpOnly(?:;|$)/i.test(value),
          strict: /;\s*SameSite=Strict(?:;|$)/i.test(value),
          rootPath: /;\s*Path=\/(?:;|$)/i.test(value),
          hostOnly: !/;\s*Domain=/i.test(value),
          clear: /;\s*Max-Age=0(?:;|$)/i.test(value),
        })),
    });
  });
  await cdp.send('Network.enable');
  return cdp;
}

async function waitForRecord(records, predicate) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const value = records.find(
      (record) =>
        record.status !== undefined && record.fetchSite !== undefined && predicate(record),
    );
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.fail('direct browser HTTP evidence was not received');
}

/** 攻击请求只用于负向；成功恢复始终委托真实 Console 页面，绝不注入浏览器禁止的头。 */
export async function verifyApiSecurity({
  context,
  platform,
  tenant,
  records,
  recoverBoth,
  onProbe = () => {},
}) {
  const probes = [];
  const server = createServer((_request, response) =>
    response
      .writeHead(200, { 'Content-Type': 'text/html' })
      .end('<!doctype html><title>Attack probe</title>'),
  );
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const extraPages = [];
  const sessions = [];
  try {
    for (const url of [
      `https://remote.${rootDomain}/static-acceptance/v1/remote.js`,
      `http://localhost:${server.address().port}/`,
      'data:text/html,<title>Opaque probe</title>',
    ]) {
      const page = await context.newPage();
      extraPages.push(page);
      sessions.push(await observeBrowserSecurity(context, page, records));
      await page.goto(url);
    }
    const [remote, illegal, opaque] = extraPages;
    const cases = [
      ['platform-missing-csrf', platform, 'PLATFORM', null, 'application/json', 'cors'],
      ['platform-invalid-csrf', platform, 'PLATFORM', 'invalid', 'application/json', 'cors'],
      ['platform-non-json', platform, 'PLATFORM', '1', 'text/plain', 'cors'],
      ['platform-slot-mismatch', platform, 'TENANT', '1', 'application/json', 'cors'],
      ['tenant-missing-csrf', tenant, 'TENANT', null, 'application/json', 'cors'],
      ['tenant-invalid-csrf', tenant, 'TENANT', 'invalid', 'application/json', 'cors'],
      ['tenant-non-json', tenant, 'TENANT', '1', 'text/plain', 'cors'],
      ['tenant-slot-mismatch', tenant, 'PLATFORM', '1', 'application/json', 'cors'],
      ['platform-unlisted-header-preflight', platform, 'PLATFORM', '1', 'application/json', 'cors'],
      ['tenant-unlisted-method-preflight', tenant, 'TENANT', '1', 'application/json', 'cors'],
      ['remote-preflight', remote, 'TENANT', '1', 'application/json', 'cors'],
      ['illegal-preflight', illegal, 'PLATFORM', '1', 'application/json', 'cors'],
      ['null-preflight', opaque, 'PLATFORM', '1', 'application/json', 'cors'],
      ['remote-direct', remote, 'TENANT', null, 'text/plain', 'no-cors'],
      ['illegal-cross-site-direct', illegal, 'PLATFORM', null, 'text/plain', 'no-cors'],
      ['null-cross-site-direct', opaque, 'PLATFORM', null, 'text/plain', 'no-cors'],
    ];
    for (const [name, page, slot, csrf, contentType, mode] of cases) {
      for (const operation of ['refresh', 'logout']) {
        const before = await context.cookies(api);
        assert.ok(
          names.every((name) => before.some((cookie) => cookie.name === name)),
          'negative probes require both live sessions',
        );
        const probe = randomUUID();
        onProbe(page, { url: `${api}/api/v1/auth/${operation}?sessionProbe=${probe}`, name });
        const read = await page.evaluate(
          async ({ api, operation, probe, slot, csrf, contentType, mode, name }) => {
            try {
              const response = await fetch(
                `${api}/api/v1/auth/${operation}?sessionProbe=${probe}`,
                {
                  method: name.includes('unlisted-method') ? 'PROPFIND' : 'POST',
                  mode,
                  credentials: 'include',
                  headers: {
                    'Content-Type': contentType,
                    ...(mode === 'cors'
                      ? { 'Idempotency-Key': `${probe.slice(0, 14)}7${probe.slice(15)}` }
                      : {}),
                    ...(csrf === null ? {} : { 'X-SF-CSRF': csrf }),
                    ...(name.includes('unlisted-header') ? { 'X-Unlisted-Probe': '1' } : {}),
                    // 仅在错误 CSRF 攻击中请求完整头集合；成功操作不手工注入 Bearer Token。
                    ...(csrf === 'invalid'
                      ? {
                          Authorization: 'Bearer invalid-attack-probe',
                          traceparent: '00-11111111111111111111111111111111-1111111111111111-01',
                          tracestate: 'probe=security',
                        }
                      : {}),
                  },
                  body: JSON.stringify({ sessionSlot: slot }),
                },
              );
              return { type: response.type, status: response.status };
            } catch {
              return { type: 'blocked', status: 0 };
            }
          },
          { api, operation, probe, slot, csrf, contentType, mode, name },
        );
        onProbe(page, null);
        const expectedMethod = name.endsWith('preflight') ? 'OPTIONS' : 'POST';
        if (context.browser().browserType().name() !== 'chromium') {
          const deadline = Date.now() + 10_000;
          let observed = [];
          while (
            !observed.some((value) => value.method === expectedMethod) &&
            Date.now() < deadline
          ) {
            observed = edgeProbeRecords(probe);
            if (!observed.some((value) => value.method === expectedMethod))
              await new Promise((resolve) => setTimeout(resolve, 100));
          }
          records.push(...observed.map((value) => ({ ...value, source: 'tls-edge' })));
        }
        const response = await waitForRecord(
          records,
          (value) => value.probe === probe && value.method === expectedMethod,
        );
        if (name.includes('unlisted-header')) {
          // Spring 保留匹配的请求头许可；未列出的头不被回显，浏览器不能发送实际操作。
          assert.equal(response.status, 200);
          assert.equal(response.allowHeaders.includes('x-unlisted-probe'), false);
          assert.equal(read.type, 'blocked');
          assert.equal(
            records.some(
              (value) =>
                value.probe === probe && value.method === 'POST' && value.status !== undefined,
            ),
            false,
          );
        } else assert.equal(response.status, 403, `${name}/${operation}: server must refuse`);
        assert.equal(
          response.cookies.length,
          0,
          'a refusal must not set or clear a refresh Cookie',
        );
        if (name.includes('cross-site')) assert.equal(response.fetchSite, 'cross-site');
        if (name.startsWith('null')) assert.equal(response.origin, 'null');
        if (['remote', 'illegal', 'null'].some((prefix) => name.startsWith(prefix))) {
          assert.equal(response.allowOrigin, null);
          assert.equal(response.allowCredentials, null);
        }
        const after = await context.cookies(api);
        assert.ok(
          JSON.stringify(before) === JSON.stringify(after),
          'refusal must not change browser sessions',
        );
        // Cookie 相等本身不足以证明服务端未撤销；每个探针后由两侧页面实际轮换并恢复。
        await recoverBoth();
        probes.push({
          name,
          operation,
          method: expectedMethod,
          status: response.status,
          read,
          cookiesUnchanged: true,
          bothSessionsRecovered: true,
          fetchSite: response.fetchSite,
          origin: response.origin,
        });
      }
    }
    return probes;
  } finally {
    for (const session of sessions) await session.detach();
    for (const page of extraPages) await page.close();
    await new Promise((resolve) => server.close(resolve));
  }
}

export function assertBrowserSecurityRecords(records) {
  const auth = records.filter(
    (record) =>
      record.host === `api.${rootDomain}` &&
      record.path.startsWith('/api/v1/auth/') &&
      record.status === 200 &&
      record.method === 'POST',
  );
  for (const slot of ['platform', 'tenant']) {
    assert.ok(
      auth.some((record) =>
        record.cookies.some(
          (cookie) => cookie.name === `__Host-sf_${slot}_refresh` && !cookie.clear,
        ),
      ),
    );
  }
  for (const record of records) {
    if (!record.host || record.status === undefined) continue;
    if (record.source === 'tls-edge') continue;
    assert.ok(
      Array.isArray(record.refreshCookies),
      'completed responses need direct request-header evidence',
    );
    if (record.host !== `api.${rootDomain}`) {
      assert.deepEqual(
        record.refreshCookies,
        [],
        'Console and Remote must not receive API Cookies',
      );
      assert.deepEqual(record.cookies ?? [], [], 'only API may issue refresh Cookies');
    }
    for (const cookie of record.cookies ?? []) {
      assert.ok(
        cookie.secure && cookie.httpOnly && cookie.strict && cookie.rootPath && cookie.hostOnly,
      );
    }
  }
  for (const origin of [`https://platform.${rootDomain}`, `https://console.${rootDomain}`]) {
    const preflight = records.find(
      (record) =>
        record.host === `api.${rootDomain}` &&
        record.method === 'OPTIONS' &&
        record.origin === origin &&
        record.status === 200 &&
        record.requestedHeaders?.length === 6,
    );
    assert.ok(preflight, 'each Console must complete a real CORS preflight');
    assert.equal(preflight.allowOrigin, origin);
    assert.equal(preflight.allowCredentials, 'true');
    assert.deepEqual(preflight.allowMethods, [
      'delete',
      'get',
      'head',
      'options',
      'patch',
      'post',
      'put',
    ]);
    assert.deepEqual(preflight.allowHeaders, preflight.requestedHeaders);
    assert.deepEqual(preflight.allowHeaders, [
      'authorization',
      'content-type',
      'idempotency-key',
      'traceparent',
      'tracestate',
      'x-sf-csrf',
    ]);
    assert.equal(preflight.maxAge, '600');
    assert.ok(preflight.vary.includes('origin'));
    const response = auth.find((record) => record.origin === origin);
    assert.ok(response);
    assert.equal(response.allowOrigin, origin);
    assert.equal(response.allowCredentials, 'true');
    assert.deepEqual(response.exposeHeaders, ['location', 'retry-after']);
  }
}
