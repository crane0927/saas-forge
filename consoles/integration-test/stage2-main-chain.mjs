import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { chromium } from 'playwright';
import {
  expectRouteAccessibility,
  expectSafeStorage,
  isAuthResponse,
  login,
} from './console-product-helpers.mjs';
import { readAcceptanceRows, verifyMainChainAudit } from './stage2-audit.mjs';

const steps = [
  'initial-platform-login',
  'entitlement',
  'tenants',
  'mail-password-setup',
  'membership-selection',
  'tenant-switch-refresh',
  'audit',
];
const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saas.forge.test';
const platformBase = `https://platform.${rootDomain}`;
const tenantBase = `https://console.${rootDomain}`;
const api = '/api/v1/';

/** 后续切片只能在 consume 回调中复用本轮内存资源；回调结束即销毁浏览器会话。 */
export async function runStage2MainChain(consume = async () => {}) {
  const project = process.env.SF_ACCEPTANCE_PROJECT;
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  assert.equal(process.env.SF_ACCEPTANCE_FRESH_VOLUMES, 'verified');
  const report = {
    runId: project,
    startedAt: new Date().toISOString(),
    commit: execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(),
    workspace: execFileSync('git', ['status', '--porcelain'], { encoding: 'utf8' })
      .trim()
      .split('\n')
      .filter(Boolean),
    freshVolumes: true,
    browserLocale: 'zh-CN',
    savedLocale: false,
    status: 'running',
    scenarios: steps.map((name) => ({ name, status: 'not-run' })),
    expectedRefusals: [],
    errors: [],
  };
  let browser;
  let context;
  let current;
  const unexpected = [];
  try {
    const java = spawnSync('java', ['-version'], { encoding: 'utf8' });
    assert.equal(java.status, 0);
    const version = java.stderr.match(/version "(17\.[^"\s]+)"/);
    assert.ok(version, '需要 JDK 17');
    report.jdk = version[1];
    report.runtimeJdk = {};
    for (const service of [
      'gateway',
      'iam-service',
      'tenant-access-service',
      'entitlement-service',
      'audit-service',
    ]) {
      const runtime = spawnSync('docker', ['exec', `${project}-${service}-1`, 'java', '-version'], {
        encoding: 'utf8',
        timeout: 10_000,
      });
      assert.equal(runtime.status, 0, '运行服务 JDK 观察失败');
      const actual = runtime.stderr.match(/version "(17\.[^"\s]+)"/);
      assert.ok(actual, '运行服务需要 JDK 17');
      report.runtimeJdk[service] = actual[1];
    }
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    report.chrome = browser.version();
    context = await browser.newContext({
      locale: 'zh-CN',
      ignoreHTTPSErrors: false,
      viewport: { width: 1440, height: 960 },
    });
    let anonymousPage;
    const refusals = [];
    const requestStarts = new WeakMap();
    const consoleErrors = [];
    const tasks = [];
    context.on('page', (page) => {
      page.on('request', (request) => requestStarts.set(request, Date.now()));
      page.on('pageerror', () => unexpected.push('uncaught-page-error'));
      page.on('requestfailed', () => unexpected.push('request-failed'));
      page.on('console', (message) => {
        if (message.type() === 'error')
          consoleErrors.push({
            page,
            at: Date.now(),
            stage: current?.name,
            url: message.location().url,
            resourceFailure:
              /^Failed to load resource: the server responded with a status of 401/.test(
                message.text(),
              ),
          });
      });
      page.on('response', (response) => {
        if (response.status() < 400) return;
        const url = new URL(response.url());
        const expected =
          page === anonymousPage &&
          response.status() === 401 &&
          url.origin === `https://api.${rootDomain}` &&
          url.pathname === `${api}auth/refresh` &&
          response.request().method() === 'POST';
        if (expected) {
          const stage = current?.name;
          const refusal = {
            page,
            stage,
            request: response.request(),
            startedAt: requestStarts.get(response.request()),
            finishedAt: Date.now(),
            consumed: false,
          };
          refusals.push(refusal);
          tasks.push(
            response
              .json()
              .then((body) => {
                if (body.code !== 'REFRESH_SESSION_INVALID')
                  unexpected.push('unexpected-anonymous-problem');
                report.expectedRefusals.push({
                  scenario: stage,
                  method: 'POST',
                  path: url.pathname,
                  status: 401,
                  requestId: refusals.indexOf(refusal) + 1,
                  startedAt: new Date(refusal.startedAt).toISOString(),
                  finishedAt: new Date(refusal.finishedAt).toISOString(),
                });
              })
              .catch(() => unexpected.push('unreadable-anonymous-problem')),
          );
        } else unexpected.push(`http-${response.status()}`);
      });
    });
    const platform = await context.newPage();
    const tenant = await context.newPage();
    const email = 'platform-admin@saas.forge.test';
    const password = `Acceptance-${randomBytes(24).toString('hex')}`;
    const recipient = `stage2-${project}@example.test`;
    const tenantPassword = `Acceptance-${randomBytes(24).toString('hex')}`;
    const operations = [];
    const resources = { tenants: [] };
    const requestMatches =
      (suffix, method = 'POST') =>
      (response) =>
        new URL(response.url()).pathname === `${api}${suffix}` &&
        response.request().method() === method;
    function observe(page, predicate) {
      const pending = page.waitForResponse(predicate);
      // 若交互先失败，关闭页面仍会拒绝等待器；消费其拒绝但不改变原 Promise 的结果。
      void pending.catch(() => {});
      return pending;
    }
    async function submit(page, suffix, label, status = 200) {
      const pending = observe(page, requestMatches(suffix));
      await page.getByRole('button', { name: label, exact: true }).press('Enter');
      const response = await pending;
      assert.equal(response.status(), status);
      return status === 204 ? undefined : response.json();
    }
    async function stage(name, action) {
      current = report.scenarios.find((item) => item.name === name);
      current.status = 'running';
      current.startedAt = new Date().toISOString();
      await action();
      await Promise.all(tasks);
      assert.equal(unexpected.length, 0, '未知网络或运行时错误阻断主链');
      current.status = 'passed';
      current.finishedAt = new Date().toISOString();
    }
    async function anonymous(page, base) {
      anonymousPage = page;
      await page.goto(base);
      await expectRouteAccessibility(page, '登录 SaaS Forge');
      assert.equal(
        await page.evaluate(() => globalThis.localStorage.getItem('sf:ui:locale')),
        null,
      );
      assert.equal(await page.evaluate(() => globalThis.navigator.language), 'zh-CN');
      anonymousPage = undefined;
    }
    async function session(page) {
      const pending = observe(page, requestMatches('auth/session', 'GET'));
      await page.getByRole('button', { name: '重新读取', exact: true }).press('Enter');
      const response = await pending;
      assert.equal(response.status(), 200);
      return response.json();
    }
    function tokenIdentity(token) {
      // 仅在内存读取真实响应中的身份引用；不保存 Token 或完整 claims。
      try {
        const identity = JSON.parse(Buffer.from(token.split('.')[1], 'base64url')).sub;
        assert.match(identity, /^[0-9a-f-]{36}$/);
        return identity;
      } catch {
        throw new Error('真实会话身份引用不可读');
      }
    }
    function family(identityId) {
      assert.match(identityId, /^[0-9a-f-]{36}$/);
      const rows = readAcceptanceRows(
        project,
        'iam_db',
        `SELECT id FROM iam_refresh_token_families WHERE identity_id = '${identityId}' AND family_purpose = 'USER_TENANT' AND revoked_at IS NULL`,
      );
      assert.equal(rows.length, 1);
      return rows[0].id;
    }
    await stage('initial-platform-login', async () => {
      await anonymous(platform, platformBase);
      const initialPassword = (await readFile(process.env.SF_INITIAL_PASSWORD_FILE, 'utf8')).trim();
      const initial = await login(platform, email, initialPassword);
      assert.equal(initial.contextState, 'PASSWORD_CHANGE_REQUIRED');
      assert.equal(Object.hasOwn(initial, 'accessToken'), false);
      await expectRouteAccessibility(platform, '设置新密码');
      await platform
        .getByLabel(/^新密码/)
        .fill(password)
        .catch(() => {
          throw new Error('密码字段不可用');
        });
      await submit(platform, 'auth/password-changes', '更新密码', 204);
      await platform.getByRole('heading', { name: '登录 SaaS Forge', exact: true }).waitFor();
      assert.equal((await login(platform, email, password)).contextState, 'ACCESS_TOKEN_ISSUED');
      await expectRouteAccessibility(platform, 'Platform 总览');
      resources.platformIdentityId = (await session(platform)).identityId;
    });
    await stage('entitlement', async () => {
      await platform.goto(`${platformBase}/quota-definitions`);
      await platform.getByRole('button', { name: '创建 max_users', exact: true }).press('Enter');
      await expectRouteAccessibility(platform, '创建 max_users');
      resources.quotaId = (
        await submit(platform, 'platform/quota-definitions', '创建 max_users', 201)
      ).id;
      await platform.waitForURL(/\/quota-definitions\/[0-9a-f-]{36}$/);
      await submit(
        platform,
        `platform/quota-definitions/${resources.quotaId}/activations`,
        '激活 max_users',
      );
      await platform.getByText('已激活', { exact: true }).waitFor();
      await platform.goto(`${platformBase}/plans/new`);
      await expectRouteAccessibility(platform, '创建套餐');
      const form = platform.getByRole('form', { name: '创建套餐', exact: true });
      await form.getByRole('textbox', { name: '编码', exact: false }).fill('stage2-plan');
      await form.getByRole('textbox', { name: '名称', exact: false }).fill('中文主链套餐');
      await form.getByRole('textbox', { name: 'max_users 上限', exact: false }).fill('2');
      resources.planId = (await submit(platform, 'platform/plans', '创建套餐', 201)).id;
      await platform.waitForURL(/\/plans\/[0-9a-f-]{36}$/);
      await submit(platform, `platform/plans/${resources.planId}/activations`, '激活套餐');
      await platform.getByText('已激活', { exact: true }).waitFor();
    });
    await stage('tenants', async () => {
      for (const name of ['中文主链甲', '中文主链乙']) {
        await platform.goto(`${platformBase}/tenants/new`);
        await expectRouteAccessibility(platform, '创建 Tenant');
        await platform.getByRole('textbox', { name: '名称', exact: false }).fill(name);
        const startedAt = new Date().toISOString();
        const created = await submit(platform, 'platform/tenants', '创建 Tenant', 201);
        operations.push({
          action: 'TENANT_CREATED',
          actor: resources.platformIdentityId,
          resource: created.id,
          tenantId: created.id,
          startedAt,
          finishedAt: new Date().toISOString(),
        });
        await platform.waitForURL(/\/tenants\/[0-9a-f-]{36}$/);
        await platform.getByRole('combobox', { name: 'Plan', exact: true }).click();
        await platform.getByText('中文主链套餐 (stage2-plan) — 2', { exact: true }).click();
        const subscription = await submit(
          platform,
          `platform/tenants/${created.id}/subscriptions`,
          '创建首个 Subscription',
          201,
        );
        assert.equal(subscription.planId, resources.planId);
        await platform.getByText('查询时有效', { exact: true }).waitFor();
        await platform.getByRole('textbox', { name: '管理员邮箱' }).fill(recipient);
        const initialized = await submit(
          platform,
          `platform/tenants/${created.id}/administrator-initializations`,
          '初始化管理员',
        );
        assert.equal(initialized.status, 'ACTIVE');
        await platform.getByText('初始化已完成', { exact: true }).waitFor();
        await platform.reload();
        await expectRouteAccessibility(platform, 'Tenant 详情');
        await platform.getByText('初始化已完成', { exact: true }).waitFor();
        const usage = platform
          .locator('dl > div')
          .filter({ has: platform.locator('dt', { hasText: 'max_users 已用量' }) })
          .locator('dd');
        assert.equal(await usage.textContent(), '1');
        resources.tenants.push({ id: created.id, name, subscriptionId: subscription.id });
      }
    });
    await stage('mail-password-setup', async () => {
      const address = execFileSync('docker', ['port', `${project}-mailpit-1`, '8025/tcp'], {
        encoding: 'utf8',
      }).trim();
      assert.match(address, /^127\.0\.0\.1:\d+$/);
      let link;
      const deadline = Date.now() + 30_000;
      while (!link && Date.now() < deadline) {
        const response = await fetch(`http://${address}/api/v1/messages`, {
          signal: AbortSignal.timeout(5000),
        });
        assert.equal(response.status, 200);
        const messages = (await response.json()).messages.filter((message) =>
          message.To.some((to) => to.Address === recipient),
        );
        for (const message of messages) {
          const detail = await fetch(`http://${address}/api/v1/message/${message.ID}`, {
            signal: AbortSignal.timeout(5000),
          });
          assert.equal(detail.status, 200);
          link = (await detail.json()).Text?.match(
            /https:\/\/[^\s]+\/password-setup#token=[A-Za-z0-9_-]{43}/,
          )?.[0];
          if (link) break;
        }
        if (!link) await setTimeout(300);
      }
      assert.equal(typeof link, 'string', '本轮真实收件人必须收到设置密码邮件');
      assert.equal(new URL(link).origin, tenantBase);
      await anonymous(tenant, tenantBase);
      await tenant.goto(link).catch(() => {
        throw new Error('邮件设置密码导航失败');
      });
      await expectRouteAccessibility(tenant, '设置密码');
      assert.equal(new URL(tenant.url()).hash, '');
      await tenant
        .getByLabel(/^新密码/)
        .fill(tenantPassword)
        .catch(() => {
          throw new Error('设置密码字段不可用');
        });
      await tenant.getByRole('button', { name: '设置密码', exact: true }).press('Enter');
      await tenant.getByText('密码已设置，请使用新密码登录。', { exact: true }).waitFor();
      await tenant.getByRole('button', { name: '登录', exact: true }).press('Enter');
    });
    let familyId;
    await stage('membership-selection', async () => {
      await tenant.getByRole('heading', { name: '登录 SaaS Forge', exact: true }).waitFor();
      const startedAt = new Date().toISOString();
      const selection = await login(tenant, recipient, tenantPassword);
      assert.equal(selection.contextState, 'CONTEXT_SELECTION_REQUIRED');
      assert.equal(Object.hasOwn(selection, 'accessToken'), false);
      assert.deepEqual(
        selection.memberships.map((item) => item.tenantId).sort(),
        resources.tenants.map((item) => item.id).sort(),
      );
      await expectRouteAccessibility(tenant, '选择 Tenant');
      const selected = await submit(tenant, 'auth/context-selections', '进入 中文主链甲');
      assert.equal(selected.tenantContext.tenantId, resources.tenants[0].id);
      await expectRouteAccessibility(tenant, 'Tenant 工作台');
      resources.tenantIdentityId = tokenIdentity(selected.accessToken);
      familyId = family(resources.tenantIdentityId);
      operations.push({
        action: 'SESSION_STARTED',
        actor: resources.tenantIdentityId,
        resource: familyId,
        startedAt,
        finishedAt: new Date().toISOString(),
      });
      resources.memberships = selection.memberships.map(({ membershipId, tenantId }) => ({
        membershipId,
        tenantId,
      }));
    });
    await stage('tenant-switch-refresh', async () => {
      await tenant.getByRole('button', { name: '切换 Tenant', exact: true }).press('Enter');
      const startedAt = new Date().toISOString();
      const refreshed = observe(tenant, isAuthResponse('refresh'));
      await submit(tenant, 'auth/tenant-switches', '切换到 中文主链乙', 204);
      const response = await refreshed;
      assert.equal(response.status(), 200);
      const switched = (await response.json()).tenantContext;
      assert.equal(switched.tenantId, resources.tenants[1].id);
      operations.push({
        action: 'TENANT_CONTEXT_SWITCHED',
        actor: resources.tenantIdentityId,
        resource: familyId,
        tenantId: switched.tenantId,
        membershipId: switched.membershipId,
        startedAt,
        finishedAt: new Date().toISOString(),
      });
      await expectRouteAccessibility(tenant, 'Tenant 工作台');
      const recovery = observe(tenant, isAuthResponse('refresh'));
      await tenant.reload();
      const recovered = await recovery;
      assert.equal(recovered.status(), 200);
      const restored = await recovered.json();
      assert.deepEqual(restored.tenantContext, switched);
      assert.equal(tokenIdentity(restored.accessToken), resources.tenantIdentityId);
      await expectRouteAccessibility(tenant, 'Tenant 工作台');
      await tenant.locator('dd').filter({ hasText: '中文主链乙' }).waitFor();
      await expectSafeStorage(tenant);
      await expectSafeStorage(platform);
    });
    await stage('audit', async () => {
      report.audit = await verifyMainChainAudit(project, operations);
    });
    await consume({
      runId: project,
      context,
      platform,
      tenant,
      resources,
      credentials: { email, password, recipient, tenantPassword },
    });
    await Promise.all(tasks);
    for (const error of consoleErrors) {
      // 一条具体匿名请求最多解释一条同页、同场景、紧邻响应的浏览器资源错误。
      const refusal = refusals.find(
        (item) =>
          !item.consumed &&
          item.page === error.page &&
          item.stage === error.stage &&
          error.at >= item.startedAt &&
          error.at <= item.finishedAt + 1000,
      );
      if (
        error.resourceFailure &&
        error.url === `https://api.${rootDomain}${api}auth/refresh` &&
        refusal
      )
        refusal.consumed = true;
      else unexpected.push('unknown-console-error');
    }
    assert.equal(unexpected.length, 0, '未知 Console 错误阻断验收');
    report.resources = resources;
    report.status = 'passed';
  } catch (error) {
    report.failureLocation = error?.stack?.match(
      /(?:stage2-[a-z-]+|console-product-helpers)\.mjs:\d+:\d+/,
    )?.[0];
    if (current?.status === 'running') current.status = 'failed';
    report.status = 'failed';
    // 不传播 Playwright 原始错误，其中可能包含密码 fill 参数或邮件 Fragment。
  } finally {
    report.errors = unexpected;
    report.finishedAt = new Date().toISOString();
    const cleanup = await Promise.allSettled([context?.close(), browser?.close()]);
    if (cleanup.some((result) => result.status === 'rejected')) {
      report.status = 'failed';
      unexpected.push('browser-cleanup-failed');
    }
    await writeFile(
      path.join(process.env.SF_BRAND_EVIDENCE_DIRECTORY, 'stage2-main-chain.json'),
      JSON.stringify(report, null, 2) + '\n',
      { mode: 0o600 },
    );
  }
  if (report.status !== 'passed')
    throw new Error(`中文主链失败：${current?.name ?? 'preflight'}；参见脱敏场景报告`);
}
