/* global document, innerWidth */
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));
const artifacts = fileURLToPath(new URL('../../../../.scratch/soybean-layout/', import.meta.url));
const server = await createServer({
  root,
  server: { host: '127.0.0.1', port: 5199, strictPort: true },
});
let browser;
try {
  await server.listen();
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('http://127.0.0.1:5199/test/layout.html');
  await page.getByText('布局测试数据', { exact: true }).waitFor();
  await page.getByRole('menuitem', { name: 'Plan 管理' }).click();
  assert.equal(
    await page
      .locator('header')
      .innerText()
      .then((text) => text.includes('Plan 管理')),
    true,
  );
  await page.getByRole('button', { name: '收起导航' }).click();
  await page.getByRole('button', { name: '展开导航' }).waitFor();
  await page.getByRole('button', { name: '展开导航' }).click();
  await page.getByRole('link', { name: '跳转到主要内容' }).focus();
  await page.keyboard.press('Enter');
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'console-main');
  await page.locator('#console-main').evaluate((element) => element.blur());
  await page.mouse.move(1000, 600);
  await mkdir(artifacts, { recursive: true });
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    assert.equal(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      true,
    );
    const sidebar = await page.locator('aside').boundingBox();
    const firstCell = await page.getByText('布局测试数据', { exact: true }).boundingBox();
    assert.ok(
      firstCell.x >= sidebar.x + sidebar.width,
      'first table column must not be covered by the sidebar',
    );
    await page.screenshot({ path: `${artifacts}/${width}.png`, fullPage: true });
  }
  assert.deepEqual(errors, []);
  console.log(
    'Layout browser checks passed: navigation, collapse, skip focus, 1440/1024 widths, no page errors.',
  );
} finally {
  await browser?.close();
  await server.close();
}
