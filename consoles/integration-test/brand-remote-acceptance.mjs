import assert from 'node:assert/strict';

/** 在已通过真实服务认证的 Tenant 页面中验证静态 Remote 消费边界。 */
export async function verifyBrandRemoteInheritance(page) {
  const favicon = page.locator('head link[rel~="icon"]');
  const inheritedFavicon = await favicon.evaluate((icon) => icon.href);
  if (page.context().browser().browserType().name() === 'firefox') {
    // Firefox 延迟加载 Shell favicon，Playwright 将其归为 image；Resource Timing
    // 则以 other 标记浏览器发起的 favicon。先等该资源完成，避免把它计入 Remote 请求。
    await page.waitForFunction(
      (url) =>
        globalThis.performance
          .getEntriesByName(url, 'resource')
          .some((entry) => entry.initiatorType === 'other' && entry.responseEnd > 0),
      inheritedFavicon,
    );
  }
  const brandRequests = [];
  const observe = (request) => {
    const pathname = new URL(request.url()).pathname;
    // Chrome 在 SPA 导航后可自行加载已有 favicon（other）。它不是 Remote 的素材请求；
    // 只豁免完全相同的 Shell favicon，Remote 的 fetch/image 或 favicon 修改仍必须失败。
    if (request.resourceType() === 'other' && request.url() === inheritedFavicon) return;
    if (pathname.startsWith('/brands/') || pathname === '/api/v1/auth/context')
      brandRequests.push({
        context: pathname === '/api/v1/auth/context',
        type: request.resourceType(),
      });
  };
  page.on('request', observe);
  try {
    await page.getByRole('link', { name: 'Remote acceptance', exact: true }).click();
    const remote = page.getByTestId('brand-remote');
    await remote.waitFor({ state: 'visible' });
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve)),
        ),
    );
    const inherited = await remote.evaluate((element) => {
      const shell = element.closest('.sf-design-system-root');
      const main = element.closest('main');
      if (!shell || !main) return false;
      return [
        '--sf-color-primary',
        '--sf-color-primary-foreground',
        '--sf-color-accent',
        '--sf-color-accent-foreground',
      ].every(
        (token) =>
          globalThis.getComputedStyle(element).getPropertyValue(token) ===
          globalThis.getComputedStyle(shell).getPropertyValue(token),
      );
    });
    const observation = {
      inherited,
      providers: await page.locator('.sf-design-system-root').count(),
      images: await remote.locator('img, link[rel~="icon"]').count(),
      faviconUnchanged:
        (await favicon.count()) === 1 &&
        (await favicon.evaluate((icon) => icon.href)) === inheritedFavicon,
      context: brandRequests.filter((request) => request.context).length,
      imageRequests: brandRequests.filter((request) => !request.context && request.type === 'image')
        .length,
      fetchRequests: brandRequests.filter((request) => !request.context && request.type === 'fetch')
        .length,
      otherRequests: brandRequests.filter(
        (request) => !request.context && !['image', 'fetch'].includes(request.type),
      ).length,
    };
    assert.deepEqual(
      observation,
      {
        inherited: true,
        providers: 1,
        images: 0,
        faviconUnchanged: true,
        context: 0,
        imageRequests: 0,
        fetchRequests: 0,
        otherRequests: 0,
      },
      `brand-remote ${Object.entries(observation)
        .map(([key, value]) => `${key}=${value}`)
        .join(' ')}`,
    );
    await page.goBack();
    await page.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
  } finally {
    page.off('request', observe);
  }
}
