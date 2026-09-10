import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import test from 'node:test';
import { chromium, firefox, webkit } from 'playwright';
import { verifyBrandRemoteInheritance } from './brand-remote-acceptance.mjs';

test('Remote observation starts after the Shell favicon and still rejects Remote asset loads', async (t) => {
  const server = createServer((request, response) => {
    if (request.url.startsWith('/brands/')) {
      // 延迟真实资源响应，确保浏览器 favicon 加载跨越 Remote 导航前的观察窗口。
      setTimeout(() => {
        response.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' });
        response.end('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16"/>');
      }, 150);
      return;
    }
    const mode = new URL(request.url, 'http://localhost').searchParams.get('mode');
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html><html><head><link rel="icon" href="/brands/blue.svg"></head>
      <body><div class="sf-design-system-root" style="--sf-color-primary:purple"><main>
      <h1>Tenant 工作台</h1><button id="switch">Switch brand</button>
      <a href="/remote">Remote acceptance</a></main></div><script>
      const main = document.querySelector('main');
      document.querySelector('button').onclick = () => {
        document.querySelector('link').href = '/brands/violet.svg';
      };
      document.querySelector('a').onclick = async (event) => {
        event.preventDefault();
        history.pushState({}, '', '/remote');
        if (${JSON.stringify(mode)} === 'image') {
          const image = new Image();
          image.src = '/brands/violet.svg';
          await image.decode();
        }
        if (${JSON.stringify(mode)} === 'fetch') await fetch('/brands/violet.svg');
        main.innerHTML = '<div data-testid="brand-remote">Remote</div>';
      };
      onpopstate = () => { main.innerHTML = '<h1>Tenant 工作台</h1>'; };
      </script></body></html>`);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const browser = await { chromium, firefox, webkit }[process.env.SF_BROWSER ?? 'chromium'].launch({
    channel: process.env.SF_BROWSER_CHANNEL,
  });
  t.after(() => browser.close());
  for (const mode of ['inherit', 'image', 'fetch']) {
    await t.test(mode, async () => {
      const page = await browser.newPage();
      try {
        await page.goto(`http://127.0.0.1:${server.address().port}/?mode=${mode}`);
        await page.getByRole('button', { name: 'Switch brand', exact: true }).click();
        if (mode === 'inherit') await verifyBrandRemoteInheritance(page);
        else
          await assert.rejects(
            verifyBrandRemoteInheritance(page),
            new RegExp(`brand-remote .*${mode}Requests=1`),
          );
      } finally {
        await page.close();
      }
    });
  }
});
