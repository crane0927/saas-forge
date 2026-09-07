import assert from 'node:assert/strict';

/** 在已通过真实服务认证的 Tenant 页面中验证静态 Remote 消费边界。 */
export async function verifyBrandRemoteInheritance(page) {
  const brandRequests = [];
  const observe = (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname.startsWith('/brands/') || pathname === '/api/v1/auth/context')
      brandRequests.push(pathname);
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
    assert.equal(
      inherited,
      true,
      'Remote must inherit the committed tokens inside shared main layout',
    );
    assert.equal(await page.locator('.sf-design-system-root').count(), 1);
    assert.equal(await remote.locator('img, link[rel~="icon"]').count(), 0);
    assert.deepEqual(brandRequests, [], 'Remote must not request context or brand assets');
    await page.goBack();
    await page.getByRole('heading', { name: 'Tenant 工作台', exact: true }).waitFor();
  } finally {
    page.off('request', observe);
  }
}
