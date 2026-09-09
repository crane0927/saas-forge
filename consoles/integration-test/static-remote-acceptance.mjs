import assert from 'node:assert/strict';

/** 真实 Tenant 入口断言，开发与后续 Fresh Compose 可复用；不代替认证验收。 */
const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN ?? 'saasforge.test';

export async function verifyStaticRemoteRendering(page) {
  const rendering = [];
  await page.goto(`https://console.${rootDomain}/acceptance/static-remote`);
  await page.getByRole('heading', { name: 'Static Remote acceptance', exact: true }).waitFor();
  for (const [version, border, width, height] of [
    ['v1', '7px', 24, 16],
    ['v2', '11px', 32, 20],
  ]) {
    await page.getByRole('button', { name: `Load ${version}`, exact: true }).click();
    await page
      .getByRole('status')
      .filter({ hasText: `${version} ready` })
      .waitFor();
    const output = page.getByText(`Remote ${version} executed`, { exact: true });
    const borderTopWidth = await output.evaluate(
      (element) => globalThis.getComputedStyle(element).borderTopWidth,
    );
    assert.equal(borderTopWidth, border);
    const image = page.getByRole('img', { name: `Remote ${version} sample` });
    const imageDimensions = await image.evaluate(async (element) => {
      await element.decode();
      return [element.naturalWidth, element.naturalHeight];
    });
    assert.deepEqual(imageDimensions, [width, height]);
    rendering.push({ version, moduleExecuted: true, borderTopWidth, imageDimensions });
  }
  return rendering;
}
