import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const consoleErrors = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });

  await page.goto('https://platform.saasforge.test/', {
    waitUntil: 'domcontentloaded',
  });
  const result = await page.evaluate(async () => {
    const response = await fetch('https://api.saasforge.test/api/v1/auth/refresh', {
      body: JSON.stringify({ sessionSlot: 'PLATFORM' }),
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': crypto.randomUUID(),
        'X-SF-CSRF': '1',
      },
      method: 'POST',
    });
    return {
      body: await response.json(),
      contentType: response.headers.get('content-type'),
      status: response.status,
    };
  });

  assert.equal(result.status, 401);
  assert.match(result.contentType ?? '', /^application\/problem\+json/u);
  assert.equal(result.body.code, 'REFRESH_SESSION_INVALID');
  assert.deepEqual(consoleErrors, []);
  console.log('BROWSER: HTTPS Edge → Gateway → IAM Refresh 返回正式未认证结果。');
} finally {
  await browser.close();
}
