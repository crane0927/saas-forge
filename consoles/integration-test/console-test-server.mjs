import { fileURLToPath } from 'node:url';
import { createServer, loadConfigFromFile } from 'vite';

// 显式覆盖正式配置，避免配置二次合并重新启用指向受控 HTTPS Host 的 HMR。
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
