import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { chromium } from 'playwright';

const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end('<!doctype html><title>Localhost Origin negative check</title>');
});
await new Promise((resolve, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', resolve);
});

const address = server.address();
assert.equal(typeof address, 'object');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const origin = `http://localhost:${address.port}`;
  const relevantResponses = [];
  page.on('response', (response) => {
    if (new URL(response.url()).hostname === 'api.saasforge.test') {
      relevantResponses.push({
        allowCredentials: response.headers()['access-control-allow-credentials'],
        allowOrigin: response.headers()['access-control-allow-origin'],
        status: response.status(),
      });
    }
  });
  await page.goto(origin);
  const rejected = await page.evaluate(async () => {
    try {
      await fetch('https://api.saasforge.test/api/v1/auth/refresh', {
        body: JSON.stringify({ sessionSlot: 'PLATFORM' }),
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID(),
          'X-SF-CSRF': '1',
        },
        method: 'POST',
      });
      return false;
    } catch {
      return true;
    }
  });

  assert.equal(rejected, true);
  assert.ok(relevantResponses.length > 0);
  assert.ok(
    relevantResponses.every(
      (response) => response.allowOrigin !== origin && response.allowCredentials !== 'true',
    ),
  );
  console.log('BROWSER-NEGATIVE: HTTP localhost Origin 已被拒绝，且未获得凭据型 CORS 授权。');
} finally {
  await browser.close();
  await new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}
