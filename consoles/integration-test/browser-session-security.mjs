import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { verifyStaticRemoteRendering } from './static-remote-acceptance.mjs';
import { observeBrowserSecurity, assertBrowserSecurityRecords } from './browser-api-security.mjs';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';
const api = `https://api.${rootDomain}`;
const cookieName = (slot) => `__Host-sf_${slot.toLowerCase()}_refresh`;
const authResponse = (operation) => (response) =>
  new URL(response.url()).origin === api &&
  new URL(response.url()).pathname === `/api/v1/auth/${operation}` &&
  response.request().method() === 'POST';

async function cookies(context) {
  return (await context.cookies()).filter((cookie) =>
    ['PLATFORM', 'TENANT'].some((slot) => cookie.name === cookieName(slot)),
  );
}

/** 只比较浏览器 Cookie 的外部状态；失败时不能把凭据放进 assert 的 actual/expected。 */
export async function assertOtherSessionUnchanged(context, slot, action) {
  const name = cookieName(slot === 'PLATFORM' ? 'TENANT' : 'PLATFORM');
  const before = (await cookies(context)).find((cookie) => cookie.name === name);
  assert.ok(before, 'the other slot must have an active Cookie');
  const result = await action();
  const after = (await cookies(context)).find((cookie) => cookie.name === name);
  assert.ok(after && before.value === after.value, 'the other slot must not rotate or clear');
  return result;
}

async function login(page, email, password) {
  await page.getByRole('heading', { name: 'Sign in to SaaS Forge', exact: true }).waitFor();
  await page.getByLabel(/^Email/).fill(email);
  await page
    .getByLabel(/^Password/)
    .fill(password)
    .catch(() => {
      throw new Error('password input unavailable');
    });
  const pending = page.waitForResponse(authResponse('login'));
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const response = await pending;
  assert.equal(response.status(), 200, 'real Console login');
  const body = await response.json();
  assert.equal(body.contextState, 'ACCESS_TOKEN_ISSUED', 'fixture requires an active membership');
}

async function recover(page, title) {
  const pending = page.waitForResponse(authResponse('refresh'));
  await page.reload();
  assert.equal((await pending).status(), 200, 'real session refresh');
  await page.getByRole('heading', { name: title, exact: true }).waitFor();
}

async function logout(page) {
  const pending = page.waitForResponse(authResponse('logout'));
  await page.getByRole('button', { name: 'Sign out', exact: true }).press('Enter');
  assert.equal((await pending).status(), 204);
  await page.getByRole('heading', { name: 'Sign in to SaaS Forge', exact: true }).waitFor();
}

/** 成功路径只操作真实页面；浏览器自行管理全部认证头和 Cookie。 */
export async function verifyBrowserSessions({
  context,
  email,
  password,
  directory,
  verifyAuthenticated,
  mode = 'fresh-compose',
}) {
  const evidence = {
    status: 'failed',
    mode,
    browserVersion: context.browser().version(),
    recordedAt: new Date().toISOString(),
    stage: 'login',
    actions: [],
    errors: [],
    expectedErrors: [],
    responses: [],
    requests: [],
  };
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await context.addInitScript(() => globalThis.localStorage.setItem('sf:ui:locale', 'en-US'));
  const platform = await context.newPage();
  const tenant = await context.newPage();
  const observers = [];
  for (const page of [platform, tenant]) {
    observers.push(await observeBrowserSecurity(context, page, evidence.requests));
    page.setDefaultTimeout(15_000);
    page.on('pageerror', () => evidence.errors.push('pageerror'));
    page.on('console', (message) => {
      if (message.type() !== 'error') return;
      if (
        evidence.stage === 'security-probes' &&
        ((message.text().includes('sessionProbe=') && message.text().includes('CORS policy')) ||
          (message.location().url.includes('sessionProbe=') &&
            /Failed to load resource: (?:the server responded with a status of 403|net::ERR_FAILED)/.test(
              message.text(),
            )))
      ) {
        evidence.expectedErrors.push('security-probe-console-error');
        return;
      }
      if (
        message.location().url === `${api}/api/v1/auth/refresh` &&
        /Failed to load resource: the server responded with a status of 401/.test(message.text())
      )
        evidence.expectedErrors.push('anonymous-refresh-401');
      else evidence.errors.push('unexpected-console-error');
    });
    page.on('response', (response) => {
      if (new URL(response.url()).origin === api)
        evidence.responses.push({
          path: new URL(response.url()).pathname,
          method: response.request().method(),
          status: response.status(),
        });
    });
    page.on('requestfailed', (request) => {
      if (new URL(request.url()).origin === api)
        evidence.responses.push({
          path: new URL(request.url()).pathname,
          method: request.method(),
          failed: true,
        });
    });
  }
  try {
    await platform.goto(`https://platform.${rootDomain}/`);
    await login(platform, email, password);
    await platform.getByRole('heading', { name: 'Platform overview', exact: true }).waitFor();
    await tenant.goto(`https://console.${rootDomain}/`);
    await login(tenant, email, password);
    await tenant.getByRole('heading', { name: 'Tenant workspace', exact: true }).waitFor();
    const inventory = await cookies(context);
    assert.equal(inventory.length, 2);
    for (const cookie of inventory) {
      assert.equal(cookie.domain, `api.${rootDomain}`);
      assert.equal(cookie.path, '/');
      assert.equal(cookie.secure, true);
      assert.equal(cookie.httpOnly, true);
      assert.equal(cookie.sameSite, 'Strict');
    }
    evidence.actions.push('both-slots-authenticated');
    await platform.screenshot({ path: path.join(directory, 'platform-authenticated.png') });
    await tenant.screenshot({ path: path.join(directory, 'tenant-authenticated.png') });
    evidence.stage = 'security-probes';
    if (verifyAuthenticated)
      evidence.probes = await verifyAuthenticated({
        context,
        platform,
        tenant,
        records: evidence.requests,
        recoverBoth: async () => {
          await recover(platform, 'Platform overview');
          await recover(tenant, 'Tenant workspace');
        },
      });
    evidence.stage = 'independent-recovery';
    for (const [slot, page, title] of [
      ['PLATFORM', platform, 'Platform overview'],
      ['TENANT', tenant, 'Tenant workspace'],
    ]) {
      const before = (await cookies(context)).find((cookie) => cookie.name === cookieName(slot));
      await assertOtherSessionUnchanged(context, slot, () => recover(page, title));
      const after = (await cookies(context)).find((cookie) => cookie.name === cookieName(slot));
      assert.ok(after && before.value !== after.value, 'recovery must rotate its own slot');
      evidence.actions.push(`${slot}-recovered-independently`);
    }
    evidence.stage = 'remote';
    const remotePage = await context.newPage();
    observers.push(await observeBrowserSecurity(context, remotePage, evidence.requests));
    try {
      evidence.remote = await verifyStaticRemoteRendering(remotePage);
    } finally {
      await remotePage.close();
    }
    evidence.stage = 'independent-logout';
    await assertOtherSessionUnchanged(context, 'PLATFORM', () => logout(platform));
    await recover(tenant, 'Tenant workspace');
    await platform.reload();
    await platform.getByRole('heading', { name: 'Sign in to SaaS Forge', exact: true }).waitFor();
    evidence.actions.push('platform-logout-tenant-still-valid');
    await login(platform, email, password);
    await platform.getByRole('heading', { name: 'Platform overview', exact: true }).waitFor();
    await assertOtherSessionUnchanged(context, 'TENANT', () => logout(tenant));
    await recover(platform, 'Platform overview');
    await tenant.reload();
    await tenant.getByRole('heading', { name: 'Sign in to SaaS Forge', exact: true }).waitFor();
    evidence.actions.push('tenant-logout-platform-still-valid');
    assert.deepEqual(evidence.errors, []);
    await logout(platform);
    assertBrowserSecurityRecords(evidence.requests);
    if (mode === 'development') {
      for (const host of ['platform', 'console'])
        assert.ok(
          evidence.requests.some(
            (record) =>
              record.host === `${host}.${rootDomain}` &&
              record.path === '/@vite/client' &&
              record.status === 200,
          ),
          'development acceptance must use the real Vite Console',
        );
    }
    evidence.status = 'passed';
    evidence.stage = 'complete';
  } finally {
    for (const observer of observers) await observer.detach().catch(() => undefined);
    await writeFile(
      path.join(directory, 'browser-sessions.json'),
      `${JSON.stringify(evidence, null, 2)}\n`,
      { mode: 0o600 },
    );
    await platform.close();
    await tenant.close();
  }
}
