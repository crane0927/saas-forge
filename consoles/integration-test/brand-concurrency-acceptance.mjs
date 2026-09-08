import assert from 'node:assert/strict';

/** 只延迟真实 GET 响应，验证同一 Runtime 的后发权威回读优先；不伪造 Token 或 Context。 */
export async function verifyLatestBrandRead(
  context,
  url,
  { source, publishLatest, restore, capture },
) {
  const peer = await context.newPage();
  peer.setDefaultTimeout(15_000);
  const oldReady = Promise.withResolvers();
  const releaseOld = Promise.withResolvers();
  const oldDelivered = Promise.withResolvers();
  // finally 关闭页面时挂起的路由也可能失败；主流程仍 await 原 Promise 报告错误。
  void oldReady.promise.catch(() => undefined);
  void oldDelivered.promise.catch(() => undefined);
  let reads = 0;
  try {
    await peer.goto(url);
    await peer
      .getByRole('navigation', { name: 'Acceptance Violet Brand 全局导航', exact: true })
      .waitFor();
    await peer.route('**/api/v1/auth/context', async (route) => {
      reads += 1;
      if (reads !== 1) return route.continue();
      try {
        const response = await route.fetch();
        assert.equal(response.status(), 200);
        oldReady.resolve();
        await releaseOld.promise;
        await route.fulfill({ response });
        oldDelivered.resolve();
      } catch (error) {
        oldReady.reject(error);
        oldDelivered.reject(error);
      }
    });
    console.info('BRAND: late-context triggering native peer synchronization');
    const requested = peer.waitForRequest('**/api/v1/auth/context', { timeout: 15_000 });
    const refresh = source.waitForResponse('**/api/v1/auth/refresh');
    await source.reload();
    const refreshed = await refresh;
    assert.equal(refreshed.status(), 200);
    assert.equal(refreshed.request().postDataJSON().sessionSlot, 'TENANT');
    await requested;
    await bounded(oldReady.promise, 'old Context response');
    console.info('BRAND: late-context old authoritative response held');
    assert.equal(await peer.title(), 'SaaS Forge Tenant Console');
    assert.equal(
      await peer.locator('.sf-design-system-root').getAttribute('data-brand'),
      'platform',
    );
    await capture(peer, 'context-reading-platform');
    await publishLatest();
    // 验收构建仅暴露原 Runtime 的公开 retryRecovery 操作，不暴露 Runtime、Token 或 Profile。
    const newestResponse = peer.waitForResponse('**/api/v1/auth/context');
    const retry = bounded(
      peer.evaluate(() => globalThis.acceptanceRetryContext()),
      'new Context recovery',
    );
    void retry.catch(() => undefined);
    await newestResponse;
    assert.equal(await retry, true);
    console.info('BRAND: late-context newer authoritative response applied');
    const latest = peer.getByRole('navigation', {
      name: 'Acceptance Newest Brand 全局导航',
      exact: true,
    });
    await latest.waitFor();
    const deliveredResponse = peer.waitForResponse('**/api/v1/auth/context');
    releaseOld.resolve();
    await bounded(oldDelivered.promise, 'old Context delivery');
    await bounded((await deliveredResponse).finished(), 'old Context completion');
    await peer.evaluate(
      () =>
        new Promise((resolve) =>
          globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve)),
        ),
    );
    assert.equal(reads, 2);
    assert.equal(await latest.count(), 1);
    assert.equal(await peer.title(), 'Acceptance Newest Brand · SaaS Forge Tenant Console');
    assert.equal(
      await peer.getByRole('img', { name: 'Acceptance Violet Brand Logo', exact: true }).count(),
      0,
    );
    await capture(peer, 'late-context-newest');
  } finally {
    releaseOld.resolve();
    await peer.close();
    await restore();
  }
}

async function bounded(operation, phase) {
  let timer;
  try {
    return await Promise.race([
      operation,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out: ${phase}`)), 15_000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}
