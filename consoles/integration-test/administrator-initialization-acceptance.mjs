/* global document, innerWidth */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';

// Browser plugin not available. 故障夹具仅作用于本次隔离 Fresh 项目，产品行为通过真实 Chrome 和服务验证。
export async function verifyAdministratorInitialization({
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
  async function createTarget(name) {
    await page.goto(`${base}/tenants/new`);
    await page.getByRole('textbox', { name: '名称' }).fill(name);
    await page.getByRole('button', { name: '创建 Tenant', exact: true }).click();
    await page.waitForURL(/\/tenants\/[0-9a-f-]{36}$/);
    const id = page.url().split('/').at(-1);
    assert.match(id, /^[0-9a-f-]{36}$/);
    await page.getByRole('combobox', { name: 'Plan', exact: true }).click();
    await page.getByText('Browser Plan (browser-plan) — 1', { exact: true }).click();
    await page.getByRole('button', { name: '创建首个 Subscription', exact: true }).click();
    await page.getByText('查询时有效', { exact: true }).waitFor();
    return id;
  }
  async function start() {
    // 复用现有 Identity；通知语义仍必须与初始化分开。
    await page.getByRole('textbox', { name: '管理员邮箱' }).fill(email);
    await page.getByRole('button', { name: '初始化管理员', exact: true }).click();
  }
  async function usage(expected) {
    await page.getByText('max_users 已用量', { exact: true }).waitFor();
    const value = page
      .locator('dl > div')
      .filter({ has: page.locator('dt', { hasText: 'max_users 已用量' }) })
      .locator('dd');
    await page.waitForFunction(
      ({ expected }) =>
        [...document.querySelectorAll('dl > div')].some(
          (row) =>
            row.querySelector('dt')?.textContent === 'max_users 已用量' &&
            row.querySelector('dd')?.textContent === expected,
        ),
      { expected: String(expected) },
    );
    assert.equal(await value.textContent(), String(expected));
  }
  try {
    await page.goto(base);
    await login(page, email, password, 'zh-CN');
    const id = await createTarget('Initialization acceptance');
    await usage(0);
    let submissions = 0;
    await page.route(
      '**/api/v1/platform/tenants/*/administrator-initializations',
      async (route) => {
        submissions++;
        const response = await route.fetch();
        assert.equal(response.status(), 200);
        // 同一浏览器请求的原 Key 重放只返回稳定结果，不能再次消费。
        const replay = await route.fetch();
        assert.equal(replay.status(), 200);
        await route.abort('failed');
      },
    );
    await start();
    await page.getByText('初始化结果待确认', { exact: true }).waitFor();
    assert.equal(submissions, 1);
    await page.unroute('**/api/v1/platform/tenants/*/administrator-initializations');
    await page.getByRole('button', { name: '重新读取初始化进度', exact: true }).click();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await page.getByText('初始管理员历史 Membership', { exact: true }).waitFor();
    await usage(1);
    await accessibility(page, 'Tenant 详情');
    await safeStorage(page);
    await capture(page, 'issue-176-initialized');
    await page.reload();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await usage(1);
    await page.route('**/api/v1/platform/tenants/*/administrator-initialization', (route) =>
      route.abort('failed'),
    );
    await page.getByRole('button', { name: '重新读取初始化进度', exact: true }).click();
    await page.getByText('初始化进度暂不可读取', { exact: true }).waitFor();
    await page.getByText('Initialization acceptance', { exact: true }).waitFor();
    await usage(1);
    await capture(page, 'issue-176-partial-failure');
    await page.unroute('**/api/v1/platform/tenants/*/administrator-initialization');
    await page.getByRole('button', { name: '重新读取初始化进度', exact: true }).click();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await selectLocale(page, 'English');
    await page.getByText('Initialization completed', { exact: true }).waitFor();
    await page.setViewportSize({ width: 320, height: 900 });
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      true,
    );
    await capture(page, 'issue-176-english-narrow');
    await page.setViewportSize({ width: 1440, height: 960 });
    await selectLocale(page, '简体中文');

    const recovering = await createTarget('Initialization recovery');
    // 真实 Entitlement 写入故障；读取仍可用，避免把前端网络拦截当成服务恢复。
    fixture('entitlement_db', 'REVOKE INSERT, UPDATE ON quota_usages FROM entitlement_app;');
    try {
      await start();
      await page.getByText('初始化结果待确认', { exact: true }).waitFor();
      const updated = fixture(
        'tenant_access_db',
        `UPDATE tenant_administrator_initialization_workflows
        SET recovery_exhausted_at = now(), next_attempt_at = now() - interval '1 second'
        WHERE tenant_id = '${recovering}' AND outcome_code IS NULL AND lease_owner IS NULL
        RETURNING workflow_id;`,
      );
      assert.match(updated, /[0-9a-f-]{36}/);
    } finally {
      fixture('entitlement_db', 'GRANT INSERT, UPDATE ON quota_usages TO entitlement_app;');
    }
    await page.reload();
    await page.getByRole('button', { name: '继续原初始化尝试', exact: true }).waitFor();
    const original = fixture(
      'tenant_access_db',
      `SELECT idempotency_key FROM tenant_administrator_initialization_workflows WHERE tenant_id = '${recovering}';`,
    );
    assert.match(original, /^[0-9a-f-]{36}$/);
    const otherEmail = 'initialization-observer@example.test';
    // 仅在隔离库准备第二个平台管理员，密码哈希直接库内复制，不输出凭据材料。
    fixture(
      'iam_db',
      `WITH added AS (
      INSERT INTO iam_identities(normalized_email, display_name, created_at)
      VALUES ('${otherEmail}', 'Initialization observer', now()) RETURNING id
    ), credential AS (
      INSERT INTO iam_credentials(identity_id, credential_type, password_hash, issued_at)
      SELECT added.id, 'PASSWORD', source.password_hash, now() FROM added
      CROSS JOIN iam_credentials source JOIN iam_identities owner ON source.identity_id = owner.id
      WHERE owner.normalized_email = '${email.replaceAll("'", "''")}'
        AND source.credential_type = 'PASSWORD' AND source.invalidated_at IS NULL
    ) INSERT INTO iam_platform_role_assignments(identity_id, role_key, assigned_at)
      SELECT id, 'PLATFORM_ADMIN', now() FROM added;`,
    );
    const observer = await browser.newContext({ ignoreHTTPSErrors: false, locale: 'zh-CN' });
    try {
      const other = await observer.newPage();
      other.on('pageerror', () => errors.push('pageerror'));
      await other.goto(base);
      await login(other, otherEmail, password, 'zh-CN');
      const observation = other.waitForResponse((response) =>
        response.url().endsWith('/administrator-initialization'),
      );
      await other.goto(`${base}/tenants/${recovering}`);
      const body = await (await observation).json();
      assert.equal(body.state, 'RECOVERY_REQUIRED');
      assert.equal(body.canContinue, false);
      assert.equal('idempotencyKey' in body, false);
      assert.equal('administratorEmail' in body, false);
      assert.equal(
        await other.getByRole('button', { name: '继续原初始化尝试', exact: true }).count(),
        0,
      );
      // 仅篡改拒绝测试的允许动作；真正恢复请求仍走第二身份的共享 Runtime，以证明服务端不信任 UI。
      await other.route(
        '**/api/v1/platform/tenants/*/administrator-initialization',
        async (route) => {
          const response = await route.fetch();
          await route.fulfill({
            response,
            json: { ...(await response.json()), canContinue: true },
          });
        },
      );
      await other.reload();
      const refused = other.waitForResponse((response) => response.url().endsWith('/recovery'));
      await other.getByRole('button', { name: '继续原初始化尝试', exact: true }).click();
      assert.equal((await refused).status(), 404);
      await capture(other, 'issue-176-other-actor-refused');
    } finally {
      await observer.close();
    }
    // 重新登录后从服务端发现原根；真正恢复还必须复核当前平台权限。
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await login(page, email, password, 'zh-CN');
    await page.goto(`${base}/tenants/${recovering}`);
    await page.getByRole('button', { name: '继续原初始化尝试', exact: true }).waitFor();
    const revoked = fixture(
      'iam_db',
      `UPDATE iam_platform_role_assignments SET revoked_at = now()
      WHERE identity_id = (SELECT id FROM iam_identities WHERE normalized_email = '${email.replaceAll("'", "''")}')
        AND role_key = 'PLATFORM_ADMIN' AND revoked_at IS NULL RETURNING id;`,
    )
      .split('\n')
      .filter((line) => /^[0-9a-f-]{36}$/.test(line));
    assert.ok(revoked.length > 0);
    try {
      const refused = page.waitForResponse((response) => response.url().endsWith('/recovery'));
      await page.getByRole('button', { name: '继续原初始化尝试', exact: true }).click();
      assert.equal((await refused).status(), 403);
    } finally {
      fixture(
        'iam_db',
        `UPDATE iam_platform_role_assignments SET revoked_at = NULL WHERE id IN (${revoked.map((value) => "'" + value + "'").join(',')});`,
      );
    }
    await page.reload();
    await page.getByRole('button', { name: '继续原初始化尝试', exact: true }).waitFor();
    await page.getByRole('button', { name: '继续原初始化尝试', exact: true }).click();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await usage(1);
    assert.equal(
      fixture(
        'tenant_access_db',
        `SELECT idempotency_key FROM tenant_administrator_initialization_workflows WHERE tenant_id = '${recovering}';`,
      ),
      original,
    );
    await capture(page, 'issue-176-recovered');

    const compensated = await createTarget('Initialization compensation');
    // 强制激活事务失败，必须调用真实 Quota 释放；移除夹具后才开始新尝试。
    fixture(
      'tenant_access_db',
      `CREATE FUNCTION issue176_reject_activation() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN IF NEW.tenant_id = '${compensated}' THEN RAISE EXCEPTION 'acceptance activation failure'; END IF; RETURN NEW; END $$;
      CREATE TRIGGER issue176_reject_activation BEFORE INSERT ON initial_tenant_administrators FOR EACH ROW EXECUTE FUNCTION issue176_reject_activation();`,
    );
    try {
      await start();
      await page.getByText('初始化结果待确认', { exact: true }).waitFor();
    } finally {
      fixture(
        'tenant_access_db',
        'DROP TRIGGER issue176_reject_activation ON initial_tenant_administrators; DROP FUNCTION issue176_reject_activation();',
      );
    }
    await page.getByRole('button', { name: '重新读取初始化进度', exact: true }).click();
    await page.getByText('补偿已完成，可以开始新的初始化尝试', { exact: true }).waitFor();
    await usage(0);
    await page.getByRole('button', { name: '开始新的初始化尝试', exact: true }).click();
    await start();
    await page.getByText('初始化已完成', { exact: true }).waitFor();
    await usage(1);
    await capture(page, 'issue-176-compensated-new-attempt');
    assert.deepEqual(errors, []);
    assert.ok(id);
  } finally {
    await context.close();
    await browser.close();
  }
}
