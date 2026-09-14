/* global document, innerWidth */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';
import { launchUnemulatedChrome } from './unemulated-chrome.mjs';

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
        'saas.forge_console_e2e',
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
  let mailPaused = false;
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
    // 保留容器 DNS，只冻结 SMTP 处理；stop 会移除服务解析，可能使 IAM 缓存上游错误地址。
    // 暂停期间仍须观察真实投递失败；恢复不重启 IAM，也不重放新的业务请求。
    docker('pause', `${project}-mailpit-1`);
    mailPaused = true;
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

    docker('unpause', `${project}-mailpit-1`);
    mailPaused = false;
    const mailAddress = docker('port', `${project}-mailpit-1`, '8025/tcp');
    assert.match(mailAddress, /^127\.0\.0\.1:\d+$/);
    const readyUntil = Date.now() + 30000;
    let mailReady = false;
    while (Date.now() < readyUntil) {
      try {
        mailReady = (await fetch(`http://${mailAddress}/api/v1/messages`)).ok;
      } catch {
        /* Mailpit HTTP service has not resumed yet. */
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
    await page.setViewportSize({ width: 1440, height: 960 });
    await selectLocale(page, '简体中文');
    // 使用真实邮件中的链接进入 Tenant Console，再验证闲置页面主动失效与重新登录。
    const setupMessage = messages.messages.find((message) =>
      message.To.some((to) => to.Address === recipient),
    );
    const mailDetail = await (
      await fetch(`http://${mailAddress}/api/v1/message/${setupMessage.ID}`)
    ).json();
    const setupLink = mailDetail.Text?.match(
      /https:\/\/[^\s]+\/password-setup#token=[A-Za-z0-9_-]{43}/,
    )?.[0];
    assert.equal(typeof setupLink, 'string', 'mail contains a setup link');
    const nativeChrome = await launchUnemulatedChrome();
    let releaseResumeRead = () => {};
    try {
      const tenantContext = nativeChrome.context;
      // 此独立浏览器只验证闲置生命周期；语言键盘路径已由上方双语产品场景覆盖。
      await tenantContext.addInitScript(() => localStorage.setItem('sf:ui:locale', 'zh-CN'));
      const lifecycleConsole = await tenantContext.newPage();
      await lifecycleConsole.setViewportSize({ width: 1440, height: 960 });
      await lifecycleConsole.bringToFront();
      await lifecycleConsole.goto(base);
      await lifecycleConsole
        .getByRole('heading', { name: '登录 SaaS Forge', exact: true })
        .waitFor();
      await login(lifecycleConsole, email, password, 'zh-CN');
      await lifecycleConsole.goto(`${base}/tenants/${id}`);
      const tenant = await tenantContext.newPage();
      const network = await tenantContext.newCDPSession(tenant);
      await network.send('Network.enable');
      const setOffline = (offline) =>
        network.send('Network.emulateNetworkConditions', {
          offline,
          latency: 0,
          downloadThroughput: -1,
          uploadThroughput: -1,
        });
      await tenant.bringToFront();
      await tenant.goto(setupLink).catch(() => {
        throw new Error('setup link navigation unavailable');
      });
      await tenant.getByRole('heading', { name: '设置密码', exact: true }).waitFor();
      assert.equal(new URL(tenant.url()).hash, '', 'challenge is immediately removed from the URL');
      await tenant
        .getByLabel(/^新密码/)
        .fill(password)
        .catch(() => {
          throw new Error('setup password field unavailable');
        });
      await tenant.getByRole('button', { name: '设置密码', exact: true }).click();
      await tenant.getByText('密码已设置，请使用新密码登录。', { exact: true }).waitFor();
      await tenant.getByRole('button', { name: '登录', exact: true }).click();
      await tenant.getByRole('heading', { name: '登录 SaaS Forge', exact: true }).waitFor();
      await login(tenant, recipient, password, 'zh-CN');
      await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
      await tenant.locator('dd').filter({ hasText: 'Notification acceptance' }).waitFor();
      await tenant.reload();
      await tenant.locator('dd').filter({ hasText: 'Notification acceptance' }).waitFor();
      await safeStorage(tenant);
      // 从测试进程观察隐藏页面，避免 Playwright 的页面 rAF 等待被 Chrome 暂停。
      async function waitForTenantDom(locator, present) {
        const deadline = Date.now() + 30000;
        while (Date.now() < deadline) {
          if ((await locator.count()) > 0 === present) return;
          await setTimeout(50);
        }
        assert.fail('Tenant access display did not reach the expected state within 30 seconds');
      }
      for (const scenario of ['long-background', 'foreground', 'offline-return', 'sleep-return']) {
        if (scenario === 'offline-return') await setOffline(true);
        await lifecycleConsole.bringToFront();
        await tenant.waitForFunction(() => document.visibilityState === 'hidden');
        if (scenario === 'long-background') {
          console.info('Waiting six minutes with the Tenant page truly hidden');
          await setTimeout(365000);
          assert.equal(await tenant.evaluate(() => document.visibilityState), 'hidden');
        }
        let resumeReadStarted;
        if (scenario === 'sleep-return') {
          await network.send('Page.setWebLifecycleState', { state: 'frozen' });
          let markStarted;
          resumeReadStarted = new Promise((resolve) => {
            markStarted = resolve;
          });
          const held = new Promise((resolve) => {
            releaseResumeRead = resolve;
          });
          await tenant.route(
            '**/api/v1/auth/context',
            async (route) => {
              markStarted();
              await held;
              await route.continue();
            },
            { times: 1 },
          );
        }
        const invalidated = tenant.waitForResponse(
          (response) =>
            new URL(response.url()).pathname === '/api/v1/auth/context' &&
            [401, 403].includes(response.status()),
          { timeout: 60000 },
        );
        await lifecycleConsole.getByRole('button', { name: '冻结公司', exact: true }).click();
        const frozen = lifecycleConsole.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).pathname.endsWith('/suspensions'),
        );
        await lifecycleConsole
          .getByRole('dialog')
          .getByRole('button', { name: '冻结公司', exact: true })
          .waitFor();
        if (scenario === 'foreground') {
          const checked = tenant.waitForResponse(
            (response) =>
              new URL(response.url()).pathname === '/api/v1/auth/context' &&
              response.status() === 200,
          );
          await tenant.bringToFront();
          await checked;
          await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
          assert.equal(await tenant.evaluate(() => document.visibilityState), 'visible');
        }
        let effectiveAt = Date.now();
        await lifecycleConsole
          .getByRole('dialog')
          .getByRole('button', { name: '冻结公司', exact: true })
          // 后台 Platform 的 rAF 已暂停；已可见且确认过的按钮通过真实输入提交，不激活标签页。
          .click({ force: scenario === 'foreground' });
        assert.equal((await frozen).status(), 200);
        if (scenario === 'offline-return') {
          await waitForTenantDom(tenant.locator('#tenant-workspace-title'), false);
          effectiveAt = Date.now();
          await setOffline(false);
        }
        if (scenario === 'sleep-return') {
          effectiveAt = Date.now();
          await network.send('Page.setWebLifecycleState', { state: 'active' });
          await tenant.bringToFront();
          await resumeReadStarted;
          await tenant.waitForFunction(() => !document.querySelector('#tenant-workspace-title'));
          // 权威读取仍被测试暂扣时已遮蔽，随后放行真实服务响应。
          releaseResumeRead();
        }
        await invalidated;
        await waitForTenantDom(tenant.getByText('Tenant 会话已结束', { exact: true }), true);
        assert.ok(
          Date.now() - effectiveAt <= 30000,
          'runnable or returning page invalidates within 30 seconds',
        );
        assert.equal(
          await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).count(),
          0,
        );
        assert.equal(
          await tenant.evaluate(() => document.visibilityState),
          ['foreground', 'sleep-return'].includes(scenario) ? 'visible' : 'hidden',
        );
        console.info(
          JSON.stringify({
            check: 'idle-tenant-suspension',
            elapsedMs: Date.now() - effectiveAt,
            scenario,
            visibility: await tenant.evaluate(() => document.visibilityState),
          }),
        );
        await lifecycleConsole.bringToFront();
        await lifecycleConsole.getByRole('button', { name: '解除冻结', exact: true }).click();
        await lifecycleConsole
          .getByRole('dialog')
          .getByRole('button', { name: '解除冻结', exact: true })
          .click();
        await lifecycleConsole
          .getByText('已解除冻结，请重新登录；其他访问限制仍然适用', { exact: true })
          .waitFor();
        assert.equal(
          await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).count(),
          0,
        );
        await tenant.bringToFront();
        await login(tenant, recipient, password, 'zh-CN');
        await tenant.locator('dd').filter({ hasText: 'Notification acceptance' }).waitFor();
        await safeStorage(tenant);
      }
    } finally {
      releaseResumeRead();
      await nativeChrome.close();
    }
    assert.deepEqual(errors, []);
  } finally {
    if (mailPaused) docker('unpause', `${project}-mailpit-1`);
    await context.close();
    await browser.close();
  }
}
