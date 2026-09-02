/* global document */
import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { randomBytes, randomUUID } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { chromium, firefox, webkit } from 'playwright';
import { verifyClientRecovery } from './console-client-acceptance.mjs';
import { verifyRequestProblemSurfaces } from './console-problem-acceptance.mjs';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';

// 此入口只访问生产构建与真实服务；不得用 route.fulfill 或忽略证书错误让正常路径通过。
test('production Consoles expose independent login paths through trusted TLS', async (t) => {
  const engine = process.env.SF_BROWSER ?? 'chromium';
  assert.ok(['chromium', 'firefox', 'webkit'].includes(engine), 'unsupported browser engine');
  const browser = await { chromium, firefox, webkit }[engine].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  const observations = [];
  const pages = [];
  t.after(async () => {
    try {
      for (const page of pages) {
        if (!page.isClosed()) {
          // 首条匿名入口测试只记录可见标题和 HTTP 状态，不记录响应体、Cookie 或请求头。
          console.info(
            JSON.stringify({ headings: await page.getByRole('heading').allTextContents() }),
          );
        }
      }
      console.info(JSON.stringify({ responses: observations }));
    } finally {
      await browser.close();
    }
  });
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  const platform = await context.newPage();
  const tenant = await context.newPage();
  pages.push(platform, tenant);
  const errors = [];
  for (const page of pages) {
    page.on('pageerror', () => errors.push('pageerror'));
    page.on('response', (response) => {
      const url = new URL(response.url());
      if (url.pathname === '/runtime-config.json' || url.pathname === '/api/v1/auth/refresh') {
        observations.push({ host: url.hostname, path: url.pathname, status: response.status() });
      }
    });
  }

  for (const [page, host, name] of [
    [platform, 'platform', 'Platform Console'],
    [tenant, 'console', 'Tenant Console'],
  ]) {
    const response = await page.goto(`https://${host}.${rootDomain}/`);
    assert.equal(response.status(), 200);
    assert.ok(await response.securityDetails(), 'the document must use TLS');
    await page.getByRole('heading', { name: `登录 ${name}`, exact: true }).waitFor();
    assert.equal(new URL(page.url()).pathname, '/login');
    assert.equal(await page.getByRole('textbox', { name: '邮箱', exact: true }).count(), 1);
    assert.equal(await page.getByLabel(/^密码/).getAttribute('type'), 'password');
  }
  assert.deepEqual(errors, []);
});

test('Platform and Tenant sessions survive independent recovery and logout after initial password change', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  const context = await browser.newContext({
    ignoreHTTPSErrors: false,
    viewport: { width: 390, height: 844 },
  });
  const diagnostics = [];
  context.on('page', (page) => {
    page.on('console', (message) => diagnostics.push(message.text()));
    page.on('pageerror', (error) => diagnostics.push(error.message));
  });
  await context.addInitScript((rootDomain) => {
    globalThis.acceptanceMessageShapes = [];
    if (globalThis.BroadcastChannel === undefined) return;
    const post = globalThis.BroadcastChannel.prototype.postMessage;
    globalThis.BroadcastChannel.prototype.postMessage = function (message) {
      if (this.name.startsWith('sf:session:')) {
        const expected =
          message?.event === 'refresh-succeeded'
            ? ['accessToken', 'contextType', 'event', 'expiresAt', 'generation']
            : ['contextType', 'event', 'generation'];
        // 观察原生消息的公开结构；不在诊断状态中复制 Token 或原始消息。
        globalThis.acceptanceMessageShapes.push({
          valid:
            typeof message === 'object' &&
            message !== null &&
            Object.keys(message).sort().join() === expected.join() &&
            ['refresh-succeeded', 'session-ended'].includes(message.event) &&
            ['PLATFORM', 'TENANT'].includes(message.contextType) &&
            this.name === `sf:session:https://api.${rootDomain}:${message.contextType}` &&
            Number.isSafeInteger(message.generation) &&
            message.generation >= 0,
          generation: message.generation,
        });
      }
      return post.call(this, message);
    };
  }, rootDomain);
  const platform = await context.newPage();
  const tenant = await context.newPage();
  const errors = [];
  for (const page of [platform, tenant]) page.on('pageerror', () => errors.push('pageerror'));
  const cookieEvents = [];
  const observeCookie = (response) => {
    const url = new URL(response.url());
    const operation = url.pathname.replace('/api/v1/auth/', '');
    if (
      url.origin !== `https://api.${rootDomain}` ||
      response.request().method() !== 'POST' ||
      !['login', 'refresh', 'password-changes'].includes(operation)
    )
      return;
    // 仅保留有序的 Cookie 属性观察；不复制 Cookie 值、请求体或任意头文本。
    cookieEvents.push(
      response.headersArray().then((headers) => {
        const cookies = headers
          .filter((header) => header.name.toLowerCase() === 'set-cookie')
          .map((header) => header.value)
          .filter((value) => value.startsWith('__Host-sf_platform_refresh='));
        const actions = new Set(
          cookies.map((value) => (/;\s*Max-Age=0(?:;|$)/i.test(value) ? 'clear' : 'set')),
        );
        const action = actions.size === 0 ? 'none' : actions.size === 1 ? [...actions][0] : 'mixed';
        const attributes =
          cookies.length > 0 &&
          cookies.every(
            (value) =>
              /;\s*Secure(?:;|$)/i.test(value) &&
              /;\s*HttpOnly(?:;|$)/i.test(value) &&
              /;\s*SameSite=Strict(?:;|$)/i.test(value) &&
              /;\s*Path=\/(?:;|$)/i.test(value) &&
              !/;\s*Domain=/i.test(value),
          );
        return `auth-cookie operation=${operation} status=${response.status()} action=${action} attributes=${attributes}`;
      }),
    );
  };
  platform.on('response', observeCookie);
  const email = 'platform-admin@saasforge.test';
  const initialPassword = (await readFile(process.env.SF_INITIAL_PASSWORD_FILE, 'utf8')).trim();
  const password = `Acceptance-${randomBytes(24).toString('hex')}`;

  await platform.goto(`https://platform.${rootDomain}/`);
  const initial = await login(platform, email, initialPassword);
  assert.equal(initial.contextState, 'PASSWORD_CHANGE_REQUIRED');
  assert.equal(Object.hasOwn(initial, 'accessToken'), false);
  const initialCookieStored = (await context.cookies(`https://api.${rootDomain}`)).some(
    (cookie) => cookie.name === '__Host-sf_platform_refresh',
  );
  await expectRouteAccessibility(platform, '设置新密码');
  await platform
    .getByLabel(/^新密码/)
    .fill(password)
    .catch(() => {
      throw new Error('new password field unavailable');
    });
  const changed = platform.waitForResponse(isAuthResponse('password-changes'));
  await platform.getByRole('button', { name: '更新密码', exact: true }).press('Enter');
  const changedResponse = await changed;
  platform.off('response', observeCookie);
  const initialCookieEvents = await Promise.all(cookieEvents);
  let changeDiagnostic;
  if (changedResponse.status() !== 204) {
    const problem = await changedResponse.json().catch(() => ({}));
    const allowedCodes = new Set([
      'PASSWORD_CHANGE_SESSION_INVALID',
      'REFRESH_SESSION_INVALID',
      'BROWSER_REQUEST_REJECTED',
      'VALIDATION_FAILED',
    ]);
    const code = allowedCodes.has(problem?.code) ? problem.code : 'OTHER';
    const cookieObserved =
      (await changedResponse.request().headerValue('cookie'))?.includes(
        '__Host-sf_platform_refresh=',
      ) ?? false;
    let requestMatches = false;
    try {
      requestMatches = changedResponse.request().postDataJSON()?.newPassword === password;
    } catch {
      // 非 JSON 请求只记布尔结果，不输出可能含密码的原文。
    }
    changeDiagnostic = `initial-password-change status=${changedResponse.status()} cookieStored=${initialCookieStored} cookieObserved=${cookieObserved} requestMatches=${requestMatches} problem=${code}`;
    changeDiagnostic += `\n${initialCookieEvents.join('\n')}`;
  }
  assert.equal(changedResponse.status(), 204, changeDiagnostic);
  await platform.getByRole('heading', { name: '登录 Platform Console', exact: true }).waitFor();
  const platformLogin = await login(platform, email, password);
  assert.equal(platformLogin.contextState, 'ACCESS_TOKEN_ISSUED');
  await platform.getByRole('heading', { name: 'Platform 总览', exact: true }).waitFor();

  // Node 侧正式 API 只用于准备 Tenant；浏览器认证断言仍由生产页面发起请求。
  // 使用同一 Identity 验证两个槽位，避免把不同账号误当成槽位隔离。
  const firstTenant = await prepareTenant(context.request, platformLogin.accessToken, email);
  await tenant.goto(`https://console.${rootDomain}/`);
  const tenantLogin = await login(tenant, email, password);
  assert.equal(tenantLogin.contextState, 'ACCESS_TOKEN_ISSUED');
  assert.ok(
    tenantLogin.accessToken !== platformLogin.accessToken,
    'slots must issue distinct tokens',
  );
  await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
  const cookieNames = (await context.cookies()).filter((cookie) =>
    cookie.name.endsWith('_refresh'),
  );
  assert.deepEqual(cookieNames.map((cookie) => cookie.name).sort(), [
    '__Host-sf_platform_refresh',
    '__Host-sf_tenant_refresh',
  ]);
  for (const cookie of cookieNames) {
    assert.equal(cookie.httpOnly, true);
    assert.equal(cookie.secure, true);
    assert.equal(cookie.domain, `api.${rootDomain}`);
    assert.equal(cookie.path, '/');
  }

  await recover(platform, 'Platform 总览');
  await recover(tenant, 'Tenant 工作台');
  await logout(platform, 'Platform Console');
  await recover(tenant, 'Tenant 工作台');
  await platform.reload();
  await platform.getByRole('heading', { name: '登录 Platform Console', exact: true }).waitFor();
  const platformRelogin = await login(platform, email, password);
  await platform.getByRole('heading', { name: 'Platform 总览', exact: true }).waitFor();
  await logout(tenant, 'Tenant Console');
  await recover(platform, 'Platform 总览');
  await tenant.reload();
  await tenant.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
  assert.deepEqual(errors, []);

  await t.test(
    'multiple Memberships require an explicit selection before entering Tenant',
    async () => {
      await prepareTenant(context.request, platformRelogin.accessToken, email, {
        planId: firstTenant.planId,
        displayName: 'Second Acceptance Tenant',
      });
      const selection = await login(tenant, email, password);
      assert.equal(selection.contextState, 'CONTEXT_SELECTION_REQUIRED');
      assert.equal(Object.hasOwn(selection, 'accessToken'), false);
      assert.equal(selection.memberships.length, 2);
      await expectRouteAccessibility(tenant, '选择 Tenant');
      assert.equal(
        await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).count(),
        0,
      );
      const selected = tenant.waitForResponse(isAuthResponse('context-selections'));
      await tenant
        .getByRole('button', { name: '进入 Second Acceptance Tenant', exact: true })
        .press('Enter');
      assert.equal((await selected).status(), 200);
      await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
      await recover(tenant, 'Tenant 工作台');
      await recover(platform, 'Platform 总览');
    },
  );

  await t.test(
    'Tenant switch commits before refresh and replaces the active navigation context',
    async () => {
      await tenant.getByRole('button', { name: '切换 Tenant', exact: true }).press('Enter');
      const sequence = [];
      const observe = (response) => {
        for (const operation of ['tenant-switches', 'refresh']) {
          if (isAuthResponse(operation)(response))
            sequence.push(`${operation}:${response.status()}`);
        }
      };
      tenant.on('response', observe);
      const committed = tenant.waitForResponse(isAuthResponse('tenant-switches'));
      const refreshed = tenant.waitForResponse(isAuthResponse('refresh'));
      await tenant
        .getByRole('button', { name: '切换到 Console Acceptance Tenant', exact: true })
        .press('Enter');
      assert.equal((await committed).status(), 204);
      const response = await refreshed;
      assert.equal(response.status(), 200);
      const body = await response.json();
      tenant.off('response', observe);
      assert.deepEqual(sequence, ['tenant-switches:204', 'refresh:200']);
      assert.equal(body.tenantContext.tenantId, firstTenant.tenantId);
      await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
      assert.equal(new URL(tenant.url()).pathname, '/');
      await tenant
        .getByRole('navigation', { name: 'Console Acceptance Tenant 全局导航', exact: true })
        .waitFor();
      assert.equal(
        await tenant
          .getByRole('navigation', { name: 'Second Acceptance Tenant 全局导航', exact: true })
          .count(),
        0,
      );
      await recover(tenant, 'Tenant 工作台');
      await tenant
        .getByRole('navigation', { name: 'Console Acceptance Tenant 全局导航', exact: true })
        .waitFor();
      await recover(platform, 'Platform 总览');
    },
  );

  await t.test(
    'a committed Tenant switch blocks old content until failed refresh is explicitly retried',
    async () => {
      const beforeSwitch = await recover(tenant, 'Tenant 工作台');
      const switchRequests = [];
      const observe = (request) => {
        if (
          new URL(request.url()).pathname === '/api/v1/auth/tenant-switches' &&
          request.method() === 'POST'
        ) {
          switchRequests.push('switch');
        }
      };
      tenant.on('request', observe);
      // 故障只发生在真实切换提交后的网络边界；切换本身和重试仍由真实服务处理。
      await tenant.route('**/api/v1/auth/refresh', (route) => route.abort('failed'), { times: 1 });
      await tenant.getByRole('button', { name: '切换 Tenant', exact: true }).press('Enter');
      const committed = tenant.waitForResponse(isAuthResponse('tenant-switches'));
      await tenant
        .getByRole('button', { name: '切换到 Second Acceptance Tenant', exact: true })
        .press('Enter');
      assert.equal((await committed).status(), 204);
      await tenant.getByRole('heading', { name: 'Tenant 切换已提交', exact: true }).waitFor();
      await tenant.getByText('目标 Tenant 会话暂时无法恢复', { exact: true }).waitFor();
      assert.equal(
        await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).count(),
        0,
      );
      assert.equal(await tenant.getByRole('navigation').count(), 0);
      // 服务端公共 HTTP 边界补充证明：旧 Token 已失效，不能作为 UI 回滚的后备凭据。
      const oldContext = await context.request
        .get(`https://api.${rootDomain}/api/v1/auth/context`, {
          headers: { Authorization: `Bearer ${beforeSwitch.accessToken}` },
        })
        .catch(() => {
          throw new Error('old context-token verification unavailable');
        });
      assert.equal(oldContext.status(), 401);
      const refreshed = tenant.waitForResponse(isAuthResponse('refresh'));
      await tenant.getByRole('button', { name: '重试完成切换', exact: true }).press('Enter');
      const response = await refreshed;
      assert.equal(response.status(), 200);
      const body = await response.json();
      assert.equal(body.tenantContext.tenantDisplayName, 'Second Acceptance Tenant');
      assert.ok(body.accessToken !== beforeSwitch.accessToken, 'recovery must issue a new token');
      await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
      await tenant
        .getByRole('navigation', { name: 'Second Acceptance Tenant 全局导航', exact: true })
        .waitFor();
      tenant.off('request', observe);
      assert.deepEqual(switchRequests, ['switch']);
      await recover(platform, 'Platform 总览');
      assert.deepEqual(errors, []);
    },
  );

  await t.test(
    'same-Origin native tabs recover concurrently and a logout wins over peer recovery',
    async () => {
      const peer = await context.newPage();
      const statuses = [];
      try {
        await peer.goto(`https://console.${rootDomain}/`);
        await peer.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        const observe = (response) => {
          if (isAuthResponse('refresh')(response)) statuses.push(response.status());
        };
        tenant.on('response', observe);
        peer.on('response', observe);
        await Promise.all([tenant.reload(), peer.reload()]);
        for (const page of [tenant, peer]) {
          const outcome = page.getByRole('heading', {
            name: /^(Tenant 工作台|暂时无法恢复会话|登录 Tenant Console)$/,
          });
          await outcome.waitFor();
          assert.equal(await outcome.textContent(), 'Tenant 工作台');
          await page
            .getByRole('navigation', { name: 'Second Acceptance Tenant 全局导航', exact: true })
            .waitFor();
        }
        tenant.off('response', observe);
        peer.off('response', observe);
        assert.ok(statuses.includes(200), 'concurrent recovery must reach the real IAM service');
        console.info(JSON.stringify({ concurrentTenantRefreshStatuses: statuses }));
        await Promise.all([logout(tenant, 'Tenant Console'), peer.reload()]);
        await peer.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
        await Promise.all([tenant.reload(), peer.reload()]);
        for (const page of [tenant, peer]) {
          await page.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
          assert.equal(
            await page.getByRole('heading', { name: 'Tenant 工作台', exact: true }).count(),
            0,
          );
        }
        await recover(platform, 'Platform 总览');
      } finally {
        await peer.close();
      }
    },
  );
  await t.test(
    'an unknown logout blocks cold recovery and delayed authentication cannot revive the session',
    async () => {
      await login(tenant, email, password);
      await expectRouteAccessibility(tenant, '选择 Tenant');
      await tenant
        .getByRole('button', { name: '进入 Second Acceptance Tenant', exact: true })
        .press('Enter');
      await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
      const peer = await context.newPage();
      const channelName = `sf:session:https://api.${rootDomain}:TENANT`;
      try {
        await peer.goto(`https://console.${rootDomain}/`);
        await peer.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        await peer.evaluate((name) => {
          const channel = new BroadcastChannel(name);
          channel.onmessage = ({ data }) => {
            globalThis.acceptanceMessage = data;
          };
        }, channelName);
        await recover(tenant, 'Tenant 工作台');
        await peer.waitForFunction(
          () => globalThis.acceptanceMessage?.event === 'refresh-succeeded',
        );
        const delayed = await peer.evaluate(() => globalThis.acceptanceMessage);
        assert.deepEqual(Object.keys(delayed).sort(), [
          'accessToken',
          'contextType',
          'event',
          'expiresAt',
          'generation',
        ]);
        assert.equal(delayed.contextType, 'TENANT');
        const logoutKeys = [];
        const observe = (request) => {
          if (isAuthRequest('logout')(request))
            logoutKeys.push(request.headers()['idempotency-key']);
        };
        tenant.on('request', observe);
        // 请求实际提交给 IAM，只丢弃返回浏览器的响应，制造真正不可判定的退出结果。
        let confirmCommit;
        const committed = new Promise((resolve) => {
          confirmCommit = resolve;
        });
        await tenant.route(
          '**/api/v1/auth/logout',
          async (route) => {
            let status = 0;
            try {
              status = (await route.fetch()).status();
            } catch {
              // 网络失败仅返回无效状态；不输出可能携带认证头的 Playwright 原始错误。
            } finally {
              await route.abort('failed');
              confirmCommit(status);
            }
          },
          { times: 1 },
        );
        await tenant.getByRole('button', { name: '退出登录', exact: true }).press('Enter');
        await tenant.getByRole('heading', { name: '退出结果尚未确认', exact: true }).waitFor();
        assert.equal(await committed, 204, 'IAM must commit before the response is lost');
        await peer.getByRole('heading', { name: '退出结果尚未确认', exact: true }).waitFor();
        const recoveries = [];
        const observeRecovery = (request) => {
          if (isAuthRequest('refresh')(request)) recoveries.push('refresh');
        };
        peer.on('request', observeRecovery);
        await peer.reload();
        await peer.getByRole('heading', { name: '退出结果尚未确认', exact: true }).waitFor();
        assert.deepEqual(recoveries, []);
        assert.equal(
          await peer.evaluate(
            (name) => globalThis.localStorage.getItem(`${name}:logoutPending`),
            channelName,
          ),
          'true',
        );
        const retried = tenant.waitForResponse(isAuthResponse('logout'));
        await tenant.getByRole('button', { name: '重试退出', exact: true }).press('Enter');
        assert.equal((await retried).status(), 204);
        tenant.off('request', observe);
        assert.equal(logoutKeys.length, 2);
        assert.ok(
          typeof logoutKeys[0] === 'string' && logoutKeys[0] === logoutKeys[1],
          'retry must retain its operation key',
        );
        for (const page of [tenant, peer])
          await page.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
        const generation = await tenant.evaluate(
          (name) => Number(globalThis.localStorage.getItem(`${name}:generation`)),
          channelName,
        );
        assert.ok(generation > delayed.generation);
        assert.equal(
          await peer.evaluate(
            (name) => globalThis.localStorage.getItem(`${name}:logoutPending`),
            channelName,
          ),
          'false',
        );
        peer.off('request', observeRecovery);
        const unexpectedRequests = [];
        const observeUnexpected = (request) => {
          if (new URL(request.url()).pathname.startsWith('/api/v1/auth/'))
            unexpectedRequests.push(request.method());
        };
        for (const page of [tenant, peer]) {
          page.on('request', observeUnexpected);
          await page.evaluate((name) => {
            const channel = new BroadcastChannel(name);
            channel.onmessage = () => {
              globalThis.acceptanceReplayObserved = true;
            };
          }, channelName);
        }
        await peer.evaluate(
          ({ name, message }) => {
            const channel = new BroadcastChannel(name);
            channel.postMessage(message);
            channel.close();
          },
          { name: channelName, message: delayed },
        );
        for (const page of [tenant, peer]) {
          await page.waitForFunction(() => globalThis.acceptanceReplayObserved === true);
          await page.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
          assert.equal(
            await page.evaluate(
              (name) => Number(globalThis.localStorage.getItem(`${name}:generation`)),
              channelName,
            ),
            generation,
          );
        }
        assert.deepEqual(unexpectedRequests, []);
        for (const page of [tenant, peer]) page.off('request', observeUnexpected);
        await recover(platform, 'Platform 总览');
      } finally {
        await peer.close();
      }
    },
  );
  await t.test(
    'Tenant brand colors, navigation and favicon follow the committed context',
    async () => {
      const selection = await login(tenant, email, password);
      const secondTenant = selection.memberships.find(
        (membership) => membership.tenantDisplayName === 'Second Acceptance Tenant',
      );
      const project = process.env.SF_ACCEPTANCE_PROJECT;
      assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
      for (const id of [firstTenant.tenantId, secondTenant.tenantId])
        assert.match(id, /^[0-9a-f-]{36}$/);
      // 品牌尚无写入 API；只在本次隔离数据卷为正式 API 创建的 Tenant 注入读模型夹具。
      // 所有验收断言仍经真实浏览器、HTTP 与渲染结果，不查询数据库验证行为。
      try {
        execFileSync(
          'docker',
          [
            'exec',
            '-i',
            `${project}-postgres-1`,
            'psql',
            '-U',
            'saasforge_console_e2e',
            '-d',
            'tenant_access_db',
            '-v',
            'ON_ERROR_STOP=1',
            '-v',
            `first_tenant=${firstTenant.tenantId}`,
            '-v',
            `second_tenant=${secondTenant.tenantId}`,
          ],
          {
            input: `INSERT INTO tenant_brand_profiles (tenant_id, display_name, favicon_url, primary_color, accent_color)
          VALUES (:'first_tenant', 'Acceptance Blue Brand', '/acceptance-brands/blue.svg', '#155EEF', '#7A5AF8'),
                 (:'second_tenant', 'Acceptance Violet Brand', '/acceptance-brands/violet.svg', '#7C3AED', '#C026D3');`,
            encoding: 'utf8',
            stdio: ['pipe', 'pipe', 'pipe'],
          },
        );
      } catch {
        throw new Error('isolated Tenant brand fixture unavailable');
      }
      await tenant.emulateMedia({ colorScheme: 'light' });
      await tenant
        .getByRole('button', { name: '进入 Console Acceptance Tenant', exact: true })
        .press('Enter');
      async function expectBrand(name, color, accent, asset) {
        await tenant.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        await tenant.getByRole('navigation', { name: `${name} 全局导航`, exact: true }).waitFor();
        assert.equal(new URL(tenant.url()).pathname, '/');
        assert.equal(
          await tenant
            .locator('.sf-design-system-root')
            .evaluate((root) =>
              globalThis.getComputedStyle(root).getPropertyValue('--sf-color-primary').trim(),
            ),
          color,
        );
        assert.equal(
          await tenant
            .locator('.sf-design-system-root')
            .evaluate((root) =>
              globalThis.getComputedStyle(root).getPropertyValue('--sf-color-accent').trim(),
            ),
          accent,
        );
        const favicon = await tenant.evaluate(async () => {
          const icon = document.querySelector('link[rel~="icon"]');
          if (icon === null) return null;
          const response = await fetch(icon.href);
          return {
            path: new URL(icon.href).pathname,
            status: response.status,
            type: response.headers.get('content-type'),
          };
        });
        assert.deepEqual(favicon, {
          path: `/acceptance-brands/${asset}.svg`,
          status: 200,
          type: 'image/svg+xml',
        });
      }
      await expectBrand('Acceptance Blue Brand', '#155EEF', '#7A5AF8', 'blue');
      await tenant.getByRole('button', { name: '切换 Tenant', exact: true }).press('Enter');
      const switched = tenant.waitForResponse(isAuthResponse('tenant-switches'));
      const refreshed = tenant.waitForResponse(isAuthResponse('refresh'));
      await tenant
        .getByRole('button', { name: '切换到 Second Acceptance Tenant', exact: true })
        .press('Enter');
      assert.equal((await switched).status(), 204);
      assert.equal((await refreshed).status(), 200);
      await expectBrand('Acceptance Violet Brand', '#7C3AED', '#C026D3', 'violet');
      assert.equal(
        await tenant
          .getByRole('navigation', { name: 'Acceptance Blue Brand 全局导航', exact: true })
          .count(),
        0,
      );
      await recover(tenant, 'Tenant 工作台');
      await expectBrand('Acceptance Violet Brand', '#7C3AED', '#C026D3', 'violet');
      await logout(tenant, 'Tenant Console');
      assert.equal(await tenant.locator('link[rel~="icon"]').count(), 0);
      await recover(platform, 'Platform 总览');
    },
  );
  await t.test(
    'IAM Lease resolves competing recovery when native coordination is unavailable',
    async () => {
      const fallback = await browser.newContext({
        ignoreHTTPSErrors: false,
        viewport: { width: 390, height: 844 },
      });
      try {
        // 只移除公开浏览器能力；并发请求和 Cookie 仍由浏览器及真实 IAM 处理。
        await fallback.addInitScript(() => {
          Object.defineProperty(navigator, 'locks', { value: undefined, configurable: true });
          Object.defineProperty(globalThis, 'BroadcastChannel', {
            value: undefined,
            configurable: true,
          });
        });
        const first = await fallback.newPage();
        const second = await fallback.newPage();
        await first.goto(`https://console.${rootDomain}/`);
        await login(first, email, password);
        await first
          .getByRole('button', { name: '进入 Second Acceptance Tenant', exact: true })
          .press('Enter');
        await first.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        await second.goto(`https://console.${rootDomain}/`);
        await second.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        for (const page of [first, second]) {
          assert.equal(
            await page.evaluate(
              () => navigator.locks === undefined && globalThis.BroadcastChannel === undefined,
            ),
            true,
          );
        }
        let release;
        const gate = new Promise((resolve) => {
          release = resolve;
        });
        const requests = [];
        await fallback.route('**/api/v1/auth/refresh', async (route) => {
          requests.push({
            page: route.request().frame().page(),
            key: await route.request().headerValue('idempotency-key'),
            at: Date.now(),
          });
          if (requests.length === 2) release();
          await gate;
          await route.continue();
        });
        const responses = [first, second].map((page) =>
          page.waitForResponse(isAuthResponse('refresh')),
        );
        await Promise.all([first.reload(), second.reload()]);
        const results = await Promise.all(responses);
        assert.deepEqual(results.map((response) => response.status()).sort(), [200, 409]);
        assert.equal(requests.length, 2);
        assert.equal(
          requests[0].key !== null &&
            requests[1].key !== null &&
            requests[0].key !== requests[1].key,
          true,
          'competing recoveries use distinct handles',
        );
        const conflict = results.find((response) => response.status() === 409);
        assert.equal((await conflict.json()).code, 'REFRESH_ROTATION_IN_PROGRESS');
        const retryAfter = Number(await conflict.headerValue('retry-after'));
        assert.ok(Number.isInteger(retryAfter) && retryAfter > 0 && retryAfter <= 5);
        const loser = conflict.request().frame().page();
        const winner = loser === first ? second : first;
        await winner.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        await loser.getByRole('heading', { name: '暂时无法恢复会话', exact: true }).waitFor();
        const original = requests.find((request) => request.page === loser);
        await loser.getByRole('button', { name: '重试恢复', exact: true }).press('Enter');
        await loser.getByRole('heading', { name: '暂时无法恢复会话', exact: true }).waitFor();
        assert.equal(requests.length, 2, 'Retry-After blocks an early retry');
        // 等待真实服务给出的重试时间；不修改 Lease 或覆盖浏览器 Cookie。
        await new Promise((resolve) => setTimeout(resolve, retryAfter * 1_000 + 100));
        const retried = loser.waitForResponse(isAuthResponse('refresh'));
        await loser.getByRole('button', { name: '重试恢复', exact: true }).press('Enter');
        assert.equal((await retried).status(), 200);
        await loser.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
        assert.equal(requests.length, 3);
        assert.equal(requests[2].key === original.key, true, 'explicit retry preserves its handle');
        await logout(loser, 'Tenant Console');
        await winner.reload();
        await winner.getByRole('heading', { name: '登录 Tenant Console', exact: true }).waitFor();
      } finally {
        await fallback.close();
      }
    },
  );
  await t.test(
    'protected navigation and a route failure retain the authenticated Shell',
    async () => {
      const navigation = platform.getByRole('link', { name: 'OAuth Client', exact: true });
      await navigation.focus();
      await navigation.press('Enter');
      await expectRouteAccessibility(platform, 'OAuth Client 管理');
      assert.equal(new URL(platform.url()).pathname, '/oauth-clients');
      await recover(platform, 'OAuth Client 管理');
      const page = await context.newPage();
      const marker = `private-route-error-${randomUUID()}`;
      let leaked = false;
      page.on('console', (message) => {
        leaked ||= message.text().includes(marker);
      });
      await page.addInitScript((marker) => {
        const descriptor = Object.getOwnPropertyDescriptor(
          globalThis.Node.prototype,
          'textContent',
        );
        Object.defineProperty(globalThis.Node.prototype, 'textContent', {
          ...descriptor,
          set(value) {
            if (this.nodeName === 'H1' && value === 'OAuth Client 管理') throw new Error(marker);
            descriptor.set.call(this, value);
          },
        });
      }, marker);
      await page.goto(`https://platform.${rootDomain}/oauth-clients`);
      await expectRouteAccessibility(page, '当前页面出现错误');
      await page
        .getByRole('navigation', { name: 'Platform Console 全局导航', exact: true })
        .waitFor();
      assert.equal((await page.locator('body').innerText()).includes(marker), false);
      const home = page.getByRole('button', { name: '返回首页', exact: true });
      await home.focus();
      await home.press('Enter');
      await expectRouteAccessibility(page, 'Platform 总览');
      assert.equal(new URL(page.url()).pathname, '/');
      assert.equal(leaked, false, 'a handled route Error must not reach production diagnostics');
      await page.close();
    },
  );
  await t.test(
    'public typed Client recovers 401 once and retains unknown mutation handles with real IAM',
    () => verifyClientRecovery(context),
  );
  await t.test(
    'browser storage, native messages and production logs retain no sensitive payloads',
    async () => {
      const api = await context.newPage();
      await api.goto(`https://api.${rootDomain}/.well-known/jwks.json`);
      for (const page of [platform, tenant, api]) {
        await expectSafeStorage(page);
      }
      for (const page of [platform, tenant]) {
        const summary = await page.evaluate(() => {
          const messages = globalThis.acceptanceMessageShapes;
          return {
            observed: messages.length > 0,
            valid: messages.every((message) => message.valid),
            monotonic: messages.every(
              (message, index) =>
                index === 0 || message.generation >= messages[index - 1].generation,
            ),
          };
        });
        assert.deepEqual(summary, { observed: true, valid: true, monotonic: true });
      }
      await api.close();
      const project = process.env.SF_ACCEPTANCE_PROJECT;
      assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
      const secrets = [
        initialPassword,
        password,
        platformLogin.accessToken,
        tenantLogin.accessToken,
        ...cookieNames.map((cookie) => cookie.value),
        'Console Acceptance Tenant',
        'Second Acceptance Tenant',
      ];
      const containsSensitiveData = (text) =>
        secrets.some((secret) => text.includes(secret)) ||
        /eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/.test(text);
      assert.equal(
        diagnostics.some(containsSensitiveData),
        false,
        'browser diagnostics contain a sensitive payload',
      );
      for (const service of [
        'gateway',
        'iam-service',
        'tenant-access-service',
        'entitlement-service',
        'audit-service',
        'platform-console',
        'tenant-console',
        'console-tls',
      ]) {
        const result = spawnSync('docker', ['logs', `${project}-${service}-1`], {
          encoding: 'utf8',
          stdio: ['ignore', 'pipe', 'pipe'],
        });
        assert.equal(result.status, 0, `production log audit unavailable for ${service}`);
        assert.equal(
          containsSensitiveData(result.stdout + result.stderr),
          false,
          `${service} diagnostics contain a sensitive payload`,
        );
      }
    },
  );
});

test('browser-managed Origins reject mismatched Intent and invalid CSRF or media type', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  const page = await context.newPage();
  await page.goto(`https://platform.${rootDomain}/`);
  await page.getByRole('heading', { name: '登录 Platform Console', exact: true }).waitFor();
  let corsRejections = 0;
  page.on('console', (message) => {
    if (
      message.type() === 'error' &&
      /cors|access-control-allow-origin|cross-origin/i.test(message.text())
    ) {
      corsRejections += 1;
    }
  });
  const attempt = (slot, csrf, contentType) =>
    page.evaluate(
      async ({ slot, csrf, contentType, rootDomain }) => {
        // 此处刻意构造不合法请求，只用于安全负向；Origin、Cookie 和 Fetch Metadata 仍由浏览器管理。
        try {
          const response = await fetch(`https://api.${rootDomain}/api/v1/auth/logout`, {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': contentType, 'X-SF-CSRF': csrf },
            body: JSON.stringify({ sessionSlot: slot }),
          });
          return response.status;
        } catch {
          return null;
        }
      },
      { slot, csrf, contentType, rootDomain },
    );
  for (const [name, slot, csrf, contentType, expected] of [
    ['mismatched Intent', 'TENANT', '1', 'application/json', 403],
    ['invalid CSRF', 'PLATFORM', 'invalid', 'application/json', null],
    ['non-JSON media type', 'PLATFORM', '1', 'text/plain', null],
  ]) {
    const controlRequest = page.waitForRequest(isAuthRequest('logout'));
    assert.equal(
      await attempt('PLATFORM', '1', 'application/json'),
      204,
      'healthy controlled request',
    );
    const control = await controlRequest;
    assert.equal(await control.headerValue('origin'), `https://platform.${rootDomain}`);
    assert.equal(await control.headerValue('sec-fetch-site'), 'same-site');
    const before = corsRejections;
    const outgoing = page.waitForRequest(isAuthRequest('logout'));
    const status = await attempt(slot, csrf, contentType);
    assert.equal(status, expected, name);
    // Gateway 在 CORS 前拒绝 CSRF/Content-Type，浏览器只暴露明确的 CORS 拒绝信号。
    // 前后成功请求排除服务不可用；服务端聚焦测试负责证明具体 403 拒绝分支。
    if (expected === null)
      assert.ok(corsRejections > before, `${name} must produce a CORS rejection`);
    const request = await outgoing;
    assert.equal(new URL(request.frame().url()).origin, `https://platform.${rootDomain}`);
    assert.equal(
      await attempt('PLATFORM', '1', 'application/json'),
      204,
      'service remains reachable',
    );
  }
  assert.equal(
    (await context.cookies()).length,
    0,
    'rejected requests must not establish a session',
  );
});

test('an opaque cross-site browser Origin is rejected before session logout', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  const page = await context.newPage();
  await page.goto(`https://platform.${rootDomain}/`);
  await page.getByRole('heading', { name: '登录 Platform Console', exact: true }).waitFor();
  await page.evaluate(() => {
    const frame = document.createElement('iframe');
    frame.sandbox.add('allow-scripts');
    frame.srcdoc = '<p>Untrusted Origin</p>';
    document.body.append(frame);
  });
  await page.frameLocator('iframe').getByText('Untrusted Origin').waitFor();
  const frame = page.frames().find((candidate) => candidate.parentFrame() !== null);
  const probe = randomUUID();
  const denied = page.waitForResponse(isAuthResponse('logout'));
  // 沙盒产生真实的 opaque Origin 和 cross-site 元数据；不伪造浏览器禁止设置的请求头。
  const result = await frame.evaluate(
    async ({ probe, rootDomain }) => {
      const response = await fetch(
        `https://api.${rootDomain}/api/v1/auth/logout?acceptanceProbe=${probe}`,
        {
          method: 'POST',
          mode: 'no-cors',
          credentials: 'include',
          body: JSON.stringify({ sessionSlot: 'PLATFORM' }),
        },
      );
      return { type: response.type, status: response.status, origin: globalThis.origin };
    },
    { probe, rootDomain },
  );
  assert.deepEqual(result, { type: 'opaque', status: 0, origin: 'null' });
  const response = await denied;
  assert.equal(response.status(), 403);
  const project = process.env.SF_ACCEPTANCE_PROJECT;
  assert.match(project ?? '', /^saas-forge-console-\d+-\d+-[a-f0-9]{6}$/);
  let logs;
  try {
    logs = execFileSync('docker', ['logs', `${project}-console-tls-1`], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch {
    throw new Error('acceptance metadata unavailable');
  }
  const observations = logs.split('\n').flatMap((line) => {
    try {
      const value = JSON.parse(line);
      return value.event === 'acceptance-browser-metadata' && value.probe === probe ? [value] : [];
    } catch {
      return [];
    }
  });
  assert.equal(observations.length, 1);
  assert.equal(observations[0].origin, 'opaque');
  assert.equal(observations[0].fetchSite, 'cross-site');
  assert.equal((await context.cookies()).length, 0);
});

test('request Problems, malformed responses and unknown network results stay within a safe form', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  await verifyRequestProblemSurfaces(browser);
});

async function expectRouteAccessibility(page, title) {
  await page.getByRole('heading', { name: title, exact: true }).waitFor();
  await page.waitForFunction((title) => document.activeElement?.textContent === title, title);
  const announcement = page.getByRole('status').filter({ hasText: title });
  assert.equal(await announcement.getAttribute('aria-live'), 'polite');
  assert.equal(await announcement.getAttribute('aria-atomic'), 'true');
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth <= globalThis.innerWidth),
    true,
  );
}

async function expectSafeStorage(page) {
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

function isAuthRequest(operation) {
  return (request) =>
    new URL(request.url()).pathname === `/api/v1/auth/${operation}` && request.method() === 'POST';
}

test('production root errors show a safe reload surface without leaking the original error', async (t) => {
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL || undefined,
  });
  t.after(() => browser.close());
  for (const [host, name] of [
    ['platform', 'Platform Console'],
    ['console', 'Tenant Console'],
  ]) {
    const context = await browser.newContext({
      ignoreHTTPSErrors: false,
      viewport: { width: 390, height: 844 },
    });
    const marker = `private-root-error-${randomUUID()}`;
    await context.addInitScript(
      ({ marker, name }) => {
        // 从 DOM 公共边界注入渲染故障，不暴露 Runtime 或增加产品测试开关。
        const descriptor = Object.getOwnPropertyDescriptor(
          globalThis.Node.prototype,
          'textContent',
        );
        Object.defineProperty(globalThis.Node.prototype, 'textContent', {
          ...descriptor,
          set(value) {
            if (this.nodeName === 'H1' && value === `登录 ${name}`) throw new Error(marker);
            descriptor.set.call(this, value);
          },
        });
      },
      { marker, name },
    );
    const page = await context.newPage();
    let leaked = false;
    page.on('console', (message) => {
      leaked ||= message.text().includes(marker);
    });
    page.on('pageerror', (error) => {
      leaked ||= error.message.includes(marker);
    });
    await page.goto(`https://${host}.${rootDomain}/`);
    await page.getByRole('heading', { name: `${name} 无法继续运行`, exact: true }).waitFor();
    assert.equal((await page.locator('body').innerText()).includes(marker), false);
    assert.equal(await page.locator('[aria-live="assertive"]').count(), 1);
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth <= globalThis.innerWidth),
      true,
    );
    const reload = page.getByRole('button', { name: '重新加载', exact: true });
    await reload.focus();
    const navigation = page.waitForEvent('domcontentloaded');
    await reload.press('Enter');
    await navigation;
    await page.getByRole('heading', { name: `${name} 无法继续运行`, exact: true }).waitFor();
    assert.equal(
      leaked,
      false,
      'production browser diagnostics must not expose the original Error',
    );
    await context.close();
  }
});

function isAuthResponse(operation) {
  return (response) =>
    new URL(response.url()).pathname === `/api/v1/auth/${operation}` &&
    response.request().method() === 'POST';
}

async function login(page, email, password) {
  const title = await page.getByRole('heading', { name: /^登录 / }).textContent();
  await expectRouteAccessibility(page, title);
  await page.getByLabel(/^邮箱/).fill(email);
  await page.keyboard.press('Tab');
  assert.equal(
    await page.getByLabel(/^密码/).evaluate((field) => field === document.activeElement),
    true,
    'email Tab order reaches the password',
  );
  // Playwright 的失败调用日志可能包含 fill 的参数，不能让随机密码进入测试日志。
  await page
    .getByLabel(/^密码/)
    .fill(password)
    .catch(() => {
      throw new Error('password field unavailable');
    });
  const pending = page.waitForResponse(isAuthResponse('login'));
  await page.getByRole('button', { name: '登录', exact: true }).press('Enter');
  const response = await pending;
  assert.equal(response.status(), 200, 'browser login status');
  return response.json();
}

async function recover(page, title) {
  const pending = page.waitForResponse(isAuthResponse('refresh'));
  await page.reload();
  const response = await pending;
  assert.equal(response.status(), 200, 'browser cold recovery status');
  await expectRouteAccessibility(page, title);
  return response.json();
}

async function logout(page, application) {
  const pending = page.waitForResponse(isAuthResponse('logout'));
  await page.getByRole('button', { name: '退出登录', exact: true }).press('Enter');
  assert.equal((await pending).status(), 204, 'browser logout status');
  await expectRouteAccessibility(page, `登录 ${application}`);
}

async function prepareTenant(api, token, email, options = {}) {
  async function post(path, data, expected) {
    const bytes = randomBytes(16);
    bytes.writeUIntBE(Date.now(), 0, 6);
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = bytes.toString('hex');
    const key = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    const response = await api
      .post(`https://api.${rootDomain}/api/v1/platform/${path}`, {
        headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
        data,
      })
      .catch(() => {
        throw new Error('fixture API transport unavailable');
      });
    if (response.status() !== expected) {
      const problem = await response.json().catch(() => undefined);
      const code =
        typeof problem?.code === 'string' && /^[A-Z][A-Z0-9_]{0,79}$/.test(problem.code)
          ? problem.code
          : 'UNAVAILABLE';
      assert.equal(response.status(), expected, `fixture ${path} status; code=${code}`);
    }
    return response.json();
  }
  let planId = options.planId;
  if (planId === undefined) {
    const quota = await post('quota-definitions', { code: 'max_users' }, 201);
    await post(`quota-definitions/${quota.id}/activations`, undefined, 200);
    const plan = await post(
      'plans',
      {
        code: 'console-acceptance',
        displayName: 'Console Acceptance',
        quotaLimits: [{ quotaDefinitionId: quota.id, limit: 2 }],
      },
      201,
    );
    await post(`plans/${plan.id}/activations`, undefined, 200);
    planId = plan.id;
  }
  const tenant = await post(
    'tenants',
    {
      displayName: options.displayName ?? 'Console Acceptance Tenant',
      expiresAt: new Date(Date.now() + 86400000).toISOString(),
    },
    201,
  );
  await post(`tenants/${tenant.id}/subscriptions`, { planId }, 201);
  const initialized = await post(
    `tenants/${tenant.id}/administrator-initializations`,
    {
      administratorEmail: email,
      administratorDisplayName: 'Console Acceptance Admin',
    },
    200,
  );
  assert.equal(initialized.status, 'ACTIVE');
  return { tenantId: tenant.id, planId };
}
