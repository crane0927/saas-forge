/* global document, window, innerWidth, getComputedStyle */
import assert from 'node:assert/strict';
import test from 'node:test';
import { createConsoleTestServer } from './console-test-server.mjs';
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import axe from 'axe-core';

// 正式路由 + 模拟 HTTP，仅用于回归，不作为真实 IAM 验收。
test('platform official login supports theme, language, authentication and logout', async (t) => {
  const { page, errors, base } = await openPlatform(t);
  let authenticated = false;
  let logins = 0;
  await page.route('**/runtime-config.json', (route) =>
    route.fulfill({ json: { schemaVersion: 1, apiBaseUrl: 'https://api.saas.forge.test' } }),
  );
  await page.route('https://api.saas.forge.test/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/login')) {
      assert.equal(route.request().postDataJSON().contextType, 'PLATFORM');
      authenticated = true;
      logins++;
    }
    if (path.endsWith('/logout')) {
      authenticated = false;
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith('/session'))
      return route.fulfill({
        json: {
          identityId: '019535d9-0000-7000-8000-000000000002',
          email: 'admin@example.test',
          displayName: 'Platform Admin',
          platformAdmin: true,
        },
      });
    if (authenticated)
      return route.fulfill({
        json: {
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'isolated-platform-test',
          tokenType: 'Bearer',
          expiresIn: 120,
        },
      });
    return route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      json: problem('UNAUTHENTICATED', 401),
    });
  });
  await page.goto(base + '/login');
  await page.getByRole('heading', { name: '登录 SaaS Forge', exact: true }).waitFor();
  await verifyPage(page, 'login-light');
  await page.getByRole('button', { name: '切换主题', exact: true }).click();
  await verifyPage(page, 'login-dark');
  assert.equal(
    await page.evaluate(() => document.documentElement.classList.contains('dark')),
    true,
  );
  assert.equal(await page.getByText('注册', { exact: true }).count(), 0);
  await page.getByRole('textbox', { name: '邮箱', exact: true }).fill('admin@example.test');
  await page.getByRole('textbox', { name: '密码', exact: true }).fill('safe-test-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('heading', { name: 'Platform 总览', exact: true }).waitFor();
  assert.equal(logins, 1);
  await verifyPage(page, 'home-dark');
  await page.getByRole('combobox', { name: 'Language / 语言' }).press('Enter');
  await page.getByRole('option', { name: 'English', exact: true }).click();
  await page.getByRole('heading', { name: 'Platform overview', exact: true }).waitFor();
  await page.getByRole('combobox', { name: 'Language / 语言' }).press('Enter');
  await page.getByRole('option', { name: '简体中文', exact: true }).click();

  await page.getByRole('button', { name: '切换主题', exact: true }).click();
  await verifyPage(page, 'home-light');
  await page.reload();
  await page.getByRole('heading', { name: 'Platform 总览', exact: true }).waitFor();
  await page.getByRole('button', { name: '退出登录', exact: true }).click();
  await page.getByRole('heading', { name: '登录 SaaS Forge', exact: true }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: '密码', exact: true }).inputValue(), '');
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  assert.ok(!storage.includes('safe-test-password') && !storage.includes('isolated-platform-test'));
  assert.deepEqual(errors, []);
});

async function openPlatform(t) {
  const server = await createConsoleTestServer('platform-console');
  await server.listen();
  t.after(() => server.close());
  const browser = await chromium.launch({ channel: process.env.SF_BROWSER_CHANNEL || undefined });
  t.after(() => browser.close());
  const page = await browser.newPage({
    locale: 'zh-CN',
    reducedMotion: 'reduce',
    viewport: { width: 1440, height: 900 },
  });
  page.setDefaultTimeout(8000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  return { page, errors, base: `http://127.0.0.1:${server.httpServer.address().port}` };
}
async function verifyPage(page, name) {
  await page.waitForFunction(() => {
    const wave = document.querySelector('.login-page > div');
    return !wave || getComputedStyle(wave).position === 'absolute';
  });
  await page.evaluate(() =>
    Promise.all(document.getAnimations().map((animation) => animation.finished.catch(() => {}))),
  );
  await page.addScriptTag({ content: axe.source });
  const violations = await page.evaluate(async () =>
    (
      await window.axe.run(document, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21aa'] },
      })
    ).violations.map((item) => ({
      id: item.id,
      nodes: item.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    })),
  );
  if (violations.length) console.log(JSON.stringify(violations));
  assert.deepEqual(violations, [], name);
  assert.equal(await page.locator('vite-error-overlay').count(), 0);
  const dimensions = await page.evaluate(() => ({
    width: innerWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  assert.equal(dimensions.width, 1440);
  assert.ok(dimensions.scroll <= dimensions.width);
  if (process.env.SF_EVIDENCE_DIR) {
    await mkdir(process.env.SF_EVIDENCE_DIR, { recursive: true });
    await page.screenshot({ path: path.join(process.env.SF_EVIDENCE_DIR, name + '.png') });
  }
}

test('platform first password change clears the old secret and requires login again', async (t) => {
  const { page, errors, base } = await openPlatform(t);
  let changes = 0;
  await page.route('**/runtime-config.json', (route) =>
    route.fulfill({ json: { schemaVersion: 1, apiBaseUrl: 'https://api.saas.forge.test' } }),
  );
  await page.route('https://api.saas.forge.test/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/login'))
      return route.fulfill({ json: { contextState: 'PASSWORD_CHANGE_REQUIRED' } });
    if (path.endsWith('/password-changes')) {
      changes++;
      assert.equal(route.request().postDataJSON().newPassword, 'new-test-password');
      return route.fulfill({ status: 204 });
    }
    return route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      json: problem('UNAUTHENTICATED', 401),
    });
  });
  await page.goto(base + '/login');
  await page.getByRole('textbox', { name: '邮箱', exact: true }).fill('admin@example.test');
  await page.getByRole('textbox', { name: '密码', exact: true }).fill('initial-test-password');
  await page.getByRole('textbox', { name: '密码', exact: true }).press('Enter');
  await page.getByRole('heading', { name: '设置新密码', exact: true }).waitFor();
  assert.ok(page.url().endsWith('/change-password'));
  assert.equal(await page.getByRole('textbox', { name: '新密码', exact: true }).inputValue(), '');
  await page.getByRole('textbox', { name: '新密码', exact: true }).fill('new-test-password');
  await page.getByRole('button', { name: '更新密码', exact: true }).click();
  await page.getByText('密码已更新，请使用新密码重新登录。', { exact: true }).waitFor();
  assert.equal(changes, 1);
  await page.getByRole('textbox', { name: '密码', exact: true }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: '密码', exact: true }).inputValue(), '');
  assert.deepEqual(errors, []);
});

test('rejected credentials and password policy preserve an actionable keyboard retry', async (t) => {
  const { page, errors, base } = await openPlatform(t);
  let logins = 0;
  await page.route('**/runtime-config.json', (route) =>
    route.fulfill({ json: { schemaVersion: 1, apiBaseUrl: 'https://api.saas.forge.test' } }),
  );
  await page.route('https://api.saas.forge.test/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/login') && ++logins > 1)
      return route.fulfill({ json: { contextState: 'PASSWORD_CHANGE_REQUIRED' } });
    return route.fulfill({
      status: path.endsWith('/password-changes') ? 400 : 401,
      contentType: 'application/problem+json',
      json: problem(
        path.endsWith('/password-changes') ? 'PASSWORD_TOO_SHORT' : 'AUTHENTICATION_FAILED',
        path.endsWith('/password-changes') ? 400 : 401,
      ),
    });
  });
  await page.goto(base + '/login');
  await page.getByRole('textbox', { name: '邮箱', exact: true }).fill('admin@example.test');
  const password = page.getByRole('textbox', { name: '密码', exact: true });
  await password.fill('wrong-test-password');
  await password.press('Enter');
  await page.getByRole('alert').waitFor();
  assert.equal(await password.evaluate((element) => element === document.activeElement), true);
  await page.getByText('邮箱或密码不正确，请重新输入。', { exact: true }).waitFor();
  await password.fill('correct-test-password');
  await password.press('Enter');
  const next = page.getByRole('textbox', { name: '新密码', exact: true });
  await next.fill('short');
  await next.press('Enter');
  await page.getByText('密码太短，请增加密码长度后重试。', { exact: true }).waitFor();
  assert.equal(await next.evaluate((element) => element === document.activeElement), true);
  assert.equal(await next.inputValue(), '');
  assert.deepEqual(errors, []);
});

test('platform rejects a tenant password setup without retaining or forwarding its challenge', async (t) => {
  const { page, errors, base } = await openPlatform(t);
  const requests = [];
  await page.route('**/runtime-config.json', (route) =>
    route.fulfill({ json: { schemaVersion: 1, apiBaseUrl: 'https://api.saas.forge.test' } }),
  );
  await page.route('https://api.saas.forge.test/**', (route) => {
    requests.push(route.request().url());
    return route.abort();
  });
  const challenge = 'x'.repeat(43);
  await page.goto(base + '/password-setup#token=' + challenge);
  await page.getByRole('alert').waitFor();
  assert.equal(new URL(page.url()).hash, '');
  assert.equal(await page.getByRole('textbox').count(), 0);
  assert.deepEqual(requests, []);
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  assert.ok(!storage.includes(challenge));
  assert.deepEqual(errors, []);
});

function problem(code, status) {
  return {
    type: 'urn:saas.forge:problem:' + code.toLowerCase().replaceAll('_', '-'),
    title: 'Rejected',
    status,
    code,
    detail: 'Rejected by the test service',
    traceId: '1234567890abcdef1234567890abcdef',
  };
}
