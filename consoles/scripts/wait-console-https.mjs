import { waitForHostHttps, recoverIsolatedTlsForwarding } from './host-https-readiness.mjs';
import { chromium } from 'playwright';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';

const rootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN;
const urls = [
  `https://platform.${rootDomain}/`,
  `https://console.${rootDomain}/`,
  `https://api.${rootDomain}/.well-known/jwks.json`,
  `https://remote.${rootDomain}/static-acceptance/v1/remote.js`,
];
// Compose health 与宿主端口转发异步收敛；仍要求四入口在浏览器正常证书校验下均返回 200。
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
try {
  const context = await browser.newContext({ ignoreHTTPSErrors: false });
  const result = await waitForHostHttps({
    probe: async () => {
      const observations = new Map();
      await Promise.all(
        urls.map(async (url) => {
          const page = await context.newPage();
          try {
            const response = await page.goto(url, {
              waitUntil: 'domcontentloaded',
              timeout: 5_000,
            });
            const status = response?.status() ?? 'NO_RESPONSE';
            observations.set(new URL(url).hostname, status);
            return status === 200;
          } catch (error) {
            // 仅保留 Chromium 网络错误码或固定分类，避免原始异常携带页面数据。
            const code = error?.message?.match(/\bnet::(ERR_[A-Z0-9_]+)\b/)?.[1];
            const category =
              error?.name === 'TimeoutError'
                ? 'BROWSER_NAVIGATION_TIMEOUT'
                : 'BROWSER_NAVIGATION_UNAVAILABLE';
            observations.set(new URL(url).hostname, code ?? category);
            return false;
          } finally {
            await page.close().catch(() => undefined);
          }
        }),
      );
      return Object.fromEntries(observations);
    },
    recoverForwarding: async () =>
      recoverIsolatedTlsForwarding({
        project: process.env.SF_ACCEPTANCE_PROJECT,
        container: process.env.SF_SECURITY_EDGE_CONTAINER,
        urls,
      }),
  });
  console.info(JSON.stringify(result));
  await writeFile(
    path.join(process.env.SF_BRAND_EVIDENCE_DIRECTORY, 'https-readiness.json'),
    JSON.stringify({ project: process.env.SF_ACCEPTANCE_PROJECT, ...result }, null, 2) + '\n',
    { mode: 0o600 },
  );
  console.info(
    'All four host HTTPS entrypoints returned 200 with browser certificate verification',
  );
} finally {
  await browser.close();
}
