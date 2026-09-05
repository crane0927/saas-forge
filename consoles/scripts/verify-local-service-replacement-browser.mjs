import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import process from 'node:process';
import { chromium } from 'playwright';

const supportedTargets = new Set([
  'gateway',
  'iam-service',
  'tenant-access-service',
  'entitlement-service',
  'audit-service',
]);
const target = process.env.SF_LOCAL_REPLACEMENT_TARGET;

if (!supportedTargets.has(target)) {
  throw new Error('SF_LOCAL_REPLACEMENT_TARGET 必须是五个受支持的本机替换目标之一。');
}

async function readRequiredSecret(name) {
  const file = process.env[name];
  if (!file) throw new Error(`缺少 ${name} 指定的受限凭据文件。`);
  const value = (await readFile(file, 'utf8')).trim();
  if (!value) throw new Error(`${name} 指定的受限凭据文件为空。`);
  return value;
}

const email = await readRequiredSecret('SF_LOCAL_REPLACEMENT_PLATFORM_EMAIL_FILE');
const password = await readRequiredSecret('SF_LOCAL_REPLACEMENT_PLATFORM_PASSWORD_FILE');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const consoleErrors = [];
  const failedRequests = [];
  const serverErrors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('requestfailed', (request) => {
    failedRequests.push(`${request.method()} ${new URL(request.url()).pathname}`);
  });
  page.on('response', (response) => {
    if (response.status() >= 500) {
      serverErrors.push(`${response.status()} ${new URL(response.url()).pathname}`);
    }
  });

  await page.goto('https://platform.saasforge.test/', {
    waitUntil: 'domcontentloaded',
  });
  await page.getByRole('heading', { name: '登录 Platform Console' }).waitFor();
  // 首次无 Cookie 的 Refresh 是预期的匿名恢复路径，不属于后续验收错误。
  consoleErrors.length = 0;
  failedRequests.length = 0;
  serverErrors.length = 0;

  const loginResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/auth/login',
  );
  await page.locator('#authentication-email').fill(email);
  await page.locator('#authentication-password').fill(password);
  await page.getByRole('button', { name: '登录' }).click();
  const loginResponse = await loginResponsePromise;
  const loginBody = await loginResponse.json().catch(() => undefined);
  assert.equal(loginResponse.status(), 200);
  assert.equal(typeof loginBody?.accessToken, 'string');
  await page.getByRole('heading', { name: 'Platform 总览' }).waitFor();
  await page.getByText('当前会话已通过 Platform 认证 Runtime 恢复或登录。').waitFor();

  const result = await page.evaluate(
    async ({ accessToken, target }) => {
      function createUuidV7(timestamp) {
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        const milliseconds = Math.max(0, Math.min(Math.trunc(timestamp), 0xffffffffffff));
        bytes[0] = Math.floor(milliseconds / 0x10000000000) & 0xff;
        bytes[1] = Math.floor(milliseconds / 0x100000000) & 0xff;
        bytes[2] = Math.floor(milliseconds / 0x1000000) & 0xff;
        bytes[3] = Math.floor(milliseconds / 0x10000) & 0xff;
        bytes[4] = Math.floor(milliseconds / 0x100) & 0xff;
        bytes[5] = milliseconds & 0xff;
        bytes[6] = ((bytes.at(6) ?? 0) & 0x0f) | 0x70;
        bytes[8] = ((bytes.at(8) ?? 0) & 0x3f) | 0x80;
        const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
      }

      if (target === 'iam-service' || target === 'audit-service') {
        return {
          operationCode: undefined,
          operationStatus: 200,
          tenantStatus: undefined,
        };
      }
      const headers = {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': createUuidV7(Date.now()),
        'X-SF-CSRF': '1',
      };
      if (target === 'tenant-access-service') {
        const response = await fetch('https://api.saasforge.test/api/v1/platform/tenants', {
          body: JSON.stringify({
            displayName: `Local replacement ${createUuidV7(Date.now())}`,
          }),
          credentials: 'include',
          headers,
          method: 'POST',
        });
        const body = await response.json().catch(() => undefined);
        return {
          operationCode: body?.code,
          operationStatus: response.status,
          tenantStatus: body?.status,
        };
      }
      const response = await fetch('https://api.saasforge.test/api/v1/platform/quota-definitions', {
        body: JSON.stringify({ code: 'max_users' }),
        credentials: 'include',
        headers,
        method: 'POST',
      });
      const body = await response.json().catch(() => undefined);
      return {
        operationCode: body?.code,
        operationStatus: response.status,
        tenantStatus: undefined,
      };
    },
    { accessToken: loginBody.accessToken, target },
  );

  if (target === 'tenant-access-service') {
    assert.equal(result.operationStatus, 201);
    assert.equal(result.tenantStatus, 'PENDING');
  } else if (target === 'gateway' || target === 'entitlement-service') {
    assert.ok(
      result.operationStatus === 201 ||
        (result.operationStatus === 409 &&
          result.operationCode === 'QUOTA_DEFINITION_ALREADY_EXISTS'),
      result.operationCode,
    );
  } else {
    assert.equal(result.operationStatus, 200);
  }
  assert.deepEqual(failedRequests, []);
  assert.deepEqual(serverErrors, []);
  assert.deepEqual(
    consoleErrors,
    result.operationStatus === 409
      ? ['Failed to load resource: the server responded with a status of 409 (Conflict)']
      : [],
  );
  console.log(
    `BROWSER: ${target} 已通过 Platform 登录表单、可见总览页和正式 API 操作验证；operation-status=${result.operationStatus}。`,
  );
} finally {
  await browser.close();
}
