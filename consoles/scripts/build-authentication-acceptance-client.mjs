import { fileURLToPath } from 'node:url';
import { build } from 'vite';

// 仅为真实 HTTP 故障验收构建公开 Runtime 接口；不进入两个 Console 的发布工件。
await build({
  configFile: false,
  root: fileURLToPath(new URL('..', import.meta.url)),
  build: {
    outDir: 'integration-test/dist',
    emptyOutDir: true,
    lib: {
      entry: fileURLToPath(new URL('../shared/app-runtime/src/index.ts', import.meta.url)),
      formats: ['es'],
      fileName: () => 'runtime.js',
    },
  },
});
