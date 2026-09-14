/* global document */
import assert from 'node:assert/strict';
const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saas.forge.test';

export async function expectRouteAccessibility(page, title, { focusedElementId } = {}) {
  await page.getByRole('heading', { name: title, exact: true, level: 1 }).waitFor();
  await page.waitForFunction(
    ({ expectedTitle, expectedId }) =>
      expectedId === undefined
        ? document.activeElement?.textContent === expectedTitle
        : document.activeElement?.id === expectedId,
    { expectedTitle: title, expectedId: focusedElementId },
  );
  const announcement = page.getByRole('status').and(page.getByText(title, { exact: true }));
  assert.equal(await announcement.getAttribute('aria-live'), 'polite');
  assert.equal(await announcement.getAttribute('aria-atomic'), 'true');
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth <= globalThis.innerWidth),
    true,
  );
}

export async function expectSafeStorage(page) {
  const summary = await page.evaluate(
    async (rootDomain) => ({
      localSafe: Object.keys(localStorage).every((key) => {
        const value = localStorage.getItem(key);
        if (
          ['PLATFORM', 'TENANT'].some(
            (slot) => key === `sf:session:https://api.${rootDomain}:${slot}:generation`,
          )
        )
          return /^\d+$/.test(value) && Number.isSafeInteger(Number(value));
        if (key === 'sf:ui:locale') return value === 'zh-CN' || value === 'en-US';
        return (
          ['PLATFORM', 'TENANT'].some(
            (slot) => key === `sf:session:https://api.${rootDomain}:${slot}:logoutPending`,
          ) &&
          (value === 'true' || value === 'false')
        );
      }),
      sessionEmpty: sessionStorage.length === 0,
      databasesEmpty: (await globalThis.indexedDB.databases()).length === 0,
      readableCookieEmpty: document.cookie === '',
    }),
    rootDomain,
  );
  // 失败只显示布尔结果；不能让存储或 Cookie 的原始值进入测试报告。
  assert.deepEqual(summary, {
    localSafe: true,
    sessionEmpty: true,
    databasesEmpty: true,
    readableCookieEmpty: true,
  });
}

export function isAuthResponse(operation) {
  return (response) =>
    new URL(response.url()).pathname === `/api/v1/auth/${operation}` &&
    response.request().method() === 'POST';
}

export async function login(page, email, password, locale = 'zh-CN', { focusedElementId } = {}) {
  const labels =
    locale === 'en-US'
      ? { title: /^Sign in to /, email: /^Email/, password: /^Password/, submit: 'Sign in' }
      : { title: /^登录 /, email: /^邮箱/, password: /^密码/, submit: '登录' };
  const title = await page.getByRole('heading', { name: labels.title }).textContent();
  await expectRouteAccessibility(page, title, { focusedElementId });
  await page.getByLabel(labels.email).fill(email);
  await page.keyboard.press('Tab');
  assert.equal(
    await page.getByLabel(labels.password).evaluate((field) => field === document.activeElement),
    true,
    'email Tab order reaches the password',
  );
  // Playwright 的失败调用日志可能包含 fill 的参数，不能让随机密码进入测试日志。
  await page
    .getByLabel(labels.password)
    .fill(password)
    .catch(() => {
      throw new Error('password field unavailable');
    });
  const [response] = await Promise.all([
    page.waitForResponse(isAuthResponse('login')),
    page.getByRole('button', { name: labels.submit, exact: true }).press('Enter'),
  ]);
  assert.equal(response.status(), 200, 'browser login status');
  return response.json();
}
