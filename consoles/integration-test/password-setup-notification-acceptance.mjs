/* global document, innerWidth */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

// Browser plugin not available. 只对本次隔离 Fresh 的真实 SMTP 服务注入故障。
export async function verifyPasswordSetupNotification({
  rootDomain,
  email,
  password,
  login,
  selectLocale,
  accessibility,
  safeStorage,
  capture,
}) {
  const project = process.env.SF_ACCEPTANCE_PROJECT;
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  const docker = (...args) =>
    execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
  function fixture(database, sql) {
    return execFileSync(
      'docker',
      [
        'exec',
        '-i',
        `${project}-postgres-1`,
        'psql',
        '-U',
        'saasforge_console_e2e',
        '-d',
        database,
        '-v',
        'ON_ERROR_STOP=1',
        '-At',
      ],
      { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
    ).trim();
  }
  async function waitForFact(database, sql) {
    const deadline = Date.now() + 45000;
    while (Date.now() < deadline) {
      if (fixture(database, sql) === 't') return;
      await setTimeout(250);
    }
    assert.fail('Expected persisted notification fact within 45 seconds');
  }
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const context = await browser.newContext({
    ignoreHTTPSErrors: false,
    locale: 'zh-CN',
    viewport: { width: 1440, height: 960 },
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', () => errors.push('pageerror'));
  const base = `https://platform.${rootDomain}`;
  let mailStopped = false;
  try {
    await page.goto(base);
    await login(page, email, password, 'zh-CN');
    await page.goto(`${base}/tenants/new`);
    await page.getByRole('textbox', { name: '名称', exact: true }).fill('Notification acceptance');
    await page.getByRole('button', { name: '创建 Tenant', exact: true }).click();
    await page.waitForURL(/\/tenants\/[0-9a-f-]{36}$/);
    const id = page.url().split('/').at(-1);
    assert.match(id, /^[0-9a-f-]{36}$/);
    const recipient = `notification-${id}@example.test`;
    await page.getByRole('combobox', { name: 'Plan', exact: true }).click();
    await page.getByText('Browser Plan (browser-plan) — 1', { exact: true }).click();
    await page.getByRole('button', { name: '创建首个 Subscription', exact: true }).click();
    await page.getByText('查询时有效', { exact: true }).waitFor();
    docker('stop', `${project}-mailpit-1`);
    mailStopped = true;
    await page.getByRole('textbox', { name: '管理员邮箱' }).fill(recipient);
    await page.getByRole('button', { name: '初始化管理员', exact: true }).click();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await waitForFact(
      'tenant_access_db',
      `SELECT EXISTS(SELECT 1 FROM tenant_administrator_initialization_workflows WHERE tenant_id = '${id}' AND EXISTS (SELECT 1 FROM password_setup_delivery_work_items work WHERE work.tenant_id = '${id}' AND work.work_status = 'PENDING') AND last_failure IS NOT NULL AND lease_owner IS NULL);`,
    );
    await waitForFact(
      'iam_db',
      `SELECT EXISTS(SELECT 1 FROM iam_password_setup_deliveries delivery JOIN iam_identities identity ON identity.id = delivery.identity_id WHERE identity.normalized_email = '${recipient}' AND delivery.status = 'PENDING');`,
    );
    // SMTP 故障已真实发生；仅加速隔离夹具的自动重试耗尽时间，不改变产品重试策略。
    fixture(
      'tenant_access_db',
      `UPDATE tenant_administrator_initialization_workflows SET recovery_exhausted_at = now(), next_attempt_at = now() WHERE tenant_id = '${id}' AND EXISTS (SELECT 1 FROM password_setup_delivery_work_items work WHERE work.tenant_id = '${id}' AND work.work_status = 'PENDING') AND lease_owner IS NULL;`,
    );
    await page.getByRole('button', { name: '刷新通知状态', exact: true }).click();
    await page.getByText('通知需处理', { exact: true }).waitFor();
    const membership = fixture(
      'tenant_access_db',
      `SELECT membership_id FROM initial_tenant_administrators WHERE tenant_id = '${id}';`,
    );
    assert.match(membership, /^[0-9a-f-]{36}$/);
    const response = page.waitForResponse(
      (value) =>
        value.url().endsWith('/administrator-password-setups') &&
        value.request().method() === 'POST',
    );
    await page.getByRole('button', { name: '重新发送通知', exact: true }).click();
    assert.equal((await response).status(), 503);
    await waitForFact(
      'tenant_access_db',
      `SELECT EXISTS(SELECT 1 FROM administrator_password_setup_workflows WHERE tenant_id = '${id}' AND outcome_code IS NULL AND last_failure IS NOT NULL AND lease_owner IS NULL);`,
    );
    fixture(
      'tenant_access_db',
      `UPDATE administrator_password_setup_workflows SET recovery_exhausted_at = now(), next_attempt_at = now() WHERE tenant_id = '${id}' AND outcome_code IS NULL AND lease_owner IS NULL;`,
    );
    const original = fixture(
      'tenant_access_db',
      `SELECT idempotency_key::text || ':' || delivery_request_id::text FROM administrator_password_setup_workflows WHERE tenant_id = '${id}';`,
    );
    await page.reload();
    await page.getByRole('button', { name: '继续原重发', exact: true }).waitFor();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await page.waitForFunction(() =>
      [...document.querySelectorAll('dl > div')].some(
        (row) =>
          row.querySelector('dt')?.textContent === 'max_users 已用量' &&
          row.querySelector('dd')?.textContent === '1',
      ),
    );
    await capture(page, 'issue-177-mail-failure-active-tenant');

    // 前序初始化验收已建立独立平台管理员；它可查看状态，不能取得或恢复原重发。
    const observer = await browser.newContext({ ignoreHTTPSErrors: false, locale: 'zh-CN' });
    try {
      const other = await observer.newPage();
      await other.goto(base);
      await login(other, 'initialization-observer@example.test', password, 'zh-CN');
      const read = other.waitForResponse((value) =>
        value.url().endsWith('/administrator-password-setup'),
      );
      await other.goto(`${base}/tenants/${id}`);
      const state = await (await read).json();
      assert.equal(state.canContinue, false);
      assert.equal(state.resendId, undefined);
      assert.equal(await other.getByRole('button', { name: '继续原重发', exact: true }).count(), 0);
      const resendId = fixture(
        'tenant_access_db',
        `SELECT workflow_id FROM administrator_password_setup_workflows WHERE tenant_id = '${id}';`,
      );
      assert.match(resendId, /^[0-9a-f-]{36}$/);
      // 伪造可见动作不能绕过服务端原操作者授权；请求仍由正式共享 Client 发送。
      await other.route('**/administrator-password-setup', async (route) => {
        const upstream = await route.fetch();
        const body = await upstream.json();
        await route.fulfill({
          response: upstream,
          json: {
            ...body,
            resendId,
            operationState: 'PENDING',
            canContinue: true,
            canResend: false,
          },
        });
      });
      await other.reload();
      const denied = other.waitForResponse(
        (value) =>
          value.url().includes('/administrator-password-setups/') &&
          value.url().endsWith('/recovery'),
      );
      await other.getByRole('button', { name: '继续原重发', exact: true }).click();
      assert.equal((await denied).status(), 404);
      await other.getByText('重发结果待确认', { exact: true }).waitFor();
      await capture(other, 'issue-177-other-actor');
    } finally {
      await observer.close();
    }

    docker('start', `${project}-mailpit-1`);
    mailStopped = false;
    const mailAddress = docker('port', `${project}-mailpit-1`, '8025/tcp');
    assert.match(mailAddress, /^127\.0\.0\.1:\d+$/);
    const readyUntil = Date.now() + 30000;
    let mailReady = false;
    while (Date.now() < readyUntil) {
      try {
        mailReady = (await fetch(`http://${mailAddress}/api/v1/messages`)).ok;
      } catch {
        /* SMTP service is still starting. */
      }
      if (mailReady) break;
      await setTimeout(250);
    }
    assert.ok(mailReady, 'mail service is ready before explicit recovery');
    const recovery = page.waitForResponse(
      (value) =>
        value.url().includes('/administrator-password-setups/') &&
        value.url().endsWith('/recovery'),
    );
    await page.getByRole('button', { name: '继续原重发', exact: true }).click();
    assert.equal((await recovery).status(), 204);
    await page.getByText('已交付邮件服务', { exact: true }).waitFor();
    assert.equal(
      fixture(
        'tenant_access_db',
        `SELECT idempotency_key::text || ':' || delivery_request_id::text FROM administrator_password_setup_workflows WHERE tenant_id = '${id}';`,
      ),
      original,
    );
    assert.equal(
      fixture(
        'tenant_access_db',
        `SELECT membership_id FROM initial_tenant_administrators WHERE tenant_id = '${id}';`,
      ),
      membership,
    );
    assert.equal(
      fixture('tenant_access_db', `SELECT tenant_status FROM tenants WHERE id = '${id}';`),
      'ACTIVE',
    );

    const mailbox = await fetch(`http://${mailAddress}/api/v1/messages`);
    assert.equal(mailbox.status, 200);
    const messages = await mailbox.json();
    assert.ok(
      messages.messages.some((message) => message.To.some((to) => to.Address === recipient)),
      'real mail service contains the setup message',
    );
    await page.reload();
    await page.getByText('已交付邮件服务', { exact: true }).waitFor();
    await page.waitForFunction(() =>
      [...document.querySelectorAll('dl > div')].some(
        (row) =>
          row.querySelector('dt')?.textContent === 'max_users 已用量' &&
          row.querySelector('dd')?.textContent === '1',
      ),
    );
    await accessibility(page, 'Tenant 详情');
    await safeStorage(page);
    await capture(page, 'issue-177-mail-service-accepted');
    await selectLocale(page, 'English');
    const englishRead = page.waitForResponse((value) =>
      new URL(value.url()).pathname.endsWith('/administrator-password-setup'),
    );
    await page.getByRole('button', { name: 'Refresh notification status', exact: true }).click();
    assert.equal((await englishRead).status(), 200);
    await page.setViewportSize({ width: 400, height: 960 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
    await page.getByText('Accepted by mail service', { exact: true }).waitFor();
    await page
      .getByText('Mail service acceptance does not confirm inbox delivery.', { exact: true })
      .waitFor();
    await capture(page, 'issue-177-english');
    assert.deepEqual(errors, []);
  } finally {
    if (mailStopped) docker('start', `${project}-mailpit-1`);
    await context.close();
    await browser.close();
  }
}
