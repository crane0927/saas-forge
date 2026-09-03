import assert from 'node:assert/strict';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';

// 使用公开 Runtime/Client 连接真实服务；此接口证据与宿主 UI 路径分别记账。
export async function verifyClientRecovery(context) {
  const page = await context.newPage();
  const statuses = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname === '/api/v1/platform/oauth-clients')
      statuses.push(response.status());
  });
  try {
    await page.goto(`https://platform.${rootDomain}/acceptance-client.html`);
    const recovered = await page.evaluate(async (rootDomain) => {
      const { createAuthenticationRuntimeAfterConfig } = await import('/acceptance-runtime.js');
      const created = createAuthenticationRuntimeAfterConfig(
        { ok: true, config: { schemaVersion: 1, apiBaseUrl: `https://api.${rootDomain}` } },
        { realm: globalThis, intent: 'PLATFORM', fetch: (input, init) => fetch(input, init) },
      );
      if (!created.ok) return false;
      globalThis.acceptanceRuntime = created.runtime;
      const result = await created.runtime.recover();
      return { ok: result.ok, status: created.runtime.getState().status };
    }, rootDomain);
    assert.deepEqual(recovered, { ok: true, status: 'authenticated' });
    const created = await page.evaluate(async () => {
      const result = await globalThis.acceptanceRuntime.client.createOAuthClient({
        request: {
          displayName: 'Acceptance Runtime Client',
          allowedScopes: new Set(['runtime:read']),
        },
      });
      // 一次性 Client Secret 不离开浏览器内存，不进入断言或日志。
      return result.ok
        ? { ok: true, clientId: result.value.clientId }
        : { ok: false, code: result.problem.code };
    });
    assert.equal(
      created.ok,
      true,
      `real typed Client fixture: ${created.code ?? 'OK'}; HTTP ${statuses.join(',')}`,
    );
    let attempts = 0;
    const tokens = [];
    const sequence = [];
    page.on('response', (response) => {
      const path = new URL(response.url()).pathname;
      if (path.endsWith(`/oauth-clients/${created.clientId}`))
        sequence.push(`get:${response.status()}`);
      if (path === '/api/v1/auth/refresh' && response.request().method() === 'POST') {
        assert.equal(response.request().postDataJSON().sessionSlot, 'PLATFORM');
        sequence.push(`refresh:${response.status()}`);
      }
    });
    await page.route(`**/api/v1/platform/oauth-clients/${created.clientId}`, async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      tokens.push(await route.request().headerValue('authorization'));
      if (++attempts === 1) {
        // 只注入一次 401 故障；Refresh 和重放的 GET 必须由真实服务完成。
        await route.fulfill({
          status: 401,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'urn:saasforge:problem:access-token-invalid',
            title: 'Unauthorized',
            status: 401,
            code: 'ACCESS_TOKEN_INVALID',
            detail: 'Acceptance fault',
            traceId: '11111111111111111111111111111111',
          }),
        });
      } else await route.continue();
    });
    const result = await page.evaluate(async (clientId) => {
      const result = await globalThis.acceptanceRuntime.client.getOAuthClient({ clientId });
      return result.ok && result.value.clientId === clientId;
    }, created.clientId);
    assert.equal(result, true);
    assert.deepEqual(sequence, ['get:401', 'refresh:200', 'get:200']);
    assert.equal(attempts, 2);
    assert.equal(
      tokens.every((token) => typeof token === 'string') && tokens[0] !== tokens[1],
      true,
      'the public Client must replay with the refreshed credential',
    );
    const mutationKeys = [];
    let committedStatus;
    let committedId;
    await page.route('**/api/v1/platform/oauth-clients', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      mutationKeys.push(await route.request().headerValue('idempotency-key'));
      if (mutationKeys.length !== 1) return route.continue();
      try {
        // 服务端实际创建后丢失响应，不能据此自动重发不可判定的变更。
        const response = await route.fetch();
        committedStatus = response.status();
        const body = await response.json();
        committedId = body.clientId;
      } catch {
        committedStatus = 0;
      }
      await route.abort('failed');
    });
    const unknown = await page.evaluate(async () => {
      globalThis.acceptanceMutationRequest = {
        displayName: 'Acceptance Lost Response Client',
        allowedScopes: new Set(['runtime:read']),
      };
      const result = await globalThis.acceptanceRuntime.client.createOAuthClient({
        request: globalThis.acceptanceMutationRequest,
      });
      if (result.ok) return { ok: true };
      globalThis.acceptanceMutationHandle = result.operationHandle;
      return { ok: false, code: result.problem.code };
    });
    assert.equal(committedStatus, 201, 'IAM must actually commit before the response is lost');
    assert.deepEqual(unknown, { ok: false, code: 'NETWORK_UNAVAILABLE' });
    assert.equal(mutationKeys.length, 1, 'unknown mutations are not automatically replayed');
    const retried = await page.evaluate(async () => {
      const result = await globalThis.acceptanceRuntime.client.createOAuthClient({
        request: globalThis.acceptanceMutationRequest,
        operationHandle: globalThis.acceptanceMutationHandle,
      });
      return result.ok ? { ok: true } : { ok: false, code: result.problem.code };
    });
    // IAM 不重复披露一次性 Secret；同键重试识别既有提交，而不是再创建一个 Client。
    assert.deepEqual(retried, { ok: false, code: 'CLIENT_SECRET_ALREADY_REVEALED' });
    assert.equal(mutationKeys.length, 2);
    assert.equal(mutationKeys[0] !== null && mutationKeys[0] === mutationKeys[1], true);
    assert.equal(
      await page.evaluate(async (clientId) => {
        const result = await globalThis.acceptanceRuntime.client.getOAuthClient({ clientId });
        return result.ok && result.value.clientId === clientId;
      }, committedId),
      true,
    );
  } finally {
    await page.close();
  }
}
