import { fileURLToPath } from 'node:url';
import { createServer, loadConfigFromFile } from 'vite';

// 显式覆盖正式配置，避免配置二次合并重新启用指向受控 HTTPS Host 的 HMR。
//
// 已知不稳定（未修复）：多个 integration-test 文件在同一个 `node --test` 调用里启动
// 同一应用的 server 时，`session-tabs.test.mjs` 的 30s `waitForFunction` 会间歇超时；
// 实测在 `console-default-realm.test.mjs` 先运行时高频复现，单独运行则稳定通过。
// 已排除的猜测：串行 `--test-concurrency=1` 无效；把 Vite `cacheDir` 按进程隔离只能把
// 失败率从约 5/7 降到约 1/4，不能消除，且会让每次启动失去热缓存。根因尚未定位。
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
