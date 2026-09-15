import { fileURLToPath } from 'node:url';
import { createServer, loadConfigFromFile } from 'vite';

// 显式覆盖正式配置，避免配置二次合并重新启用指向受控 HTTPS Host 的 HMR。
//
// 这里按目录显式指定 root 是刻意的：Vite 的 root 默认取 cwd，那样测试结果会随调用
// 目录变化。`session-tabs.test.mjs` 曾因此从包目录运行时落空——harness 地址被 Vite 的
// SPA fallback 换成了该应用的 index.html（状态码仍为 200），页面里没有验收句柄，
// `waitForFunction` 只能等到 30s 超时。新增浏览器集成测试请沿用显式 root。
export async function createConsoleTestServer(directory) {
  const root = fileURLToPath(new URL(`../${directory}/`, import.meta.url));
  const { config } = await loadConfigFromFile(
    { command: 'serve', mode: 'test' },
    root + 'vite.config.ts',
  );
  return createServer({
    ...config,
    root,
    configFile: false,
    server: { ...config.server, host: '127.0.0.1', port: 0, strictPort: false, hmr: false },
  });
}
