import { fileURLToPath } from 'node:url';
import { globSync, readFileSync } from 'node:fs';
import vue from '@vitejs/plugin-vue';
import UnoCSS from '@unocss/vite';
import presetWind3 from '@unocss/preset-wind3';
import { defineConfig, type Plugin } from 'vite';

const DEVELOPMENT_API_ORIGIN = 'https://api.saas.forge.test';

function controlledDevelopmentRuntimeConfig(): Plugin {
  return {
    name: 'controlled-development-runtime-config',
    apply: 'serve',
    configureServer(server) {
      // 浏览器必须经受信 HTTPS Edge 访问；默认 Local URL 仅供内部转发。
      server.printUrls = () => {
        server.config.logger.info('  ➜  浏览器入口: https://platform.saas.forge.test/');
        for (const url of server.resolvedUrls?.local ?? []) {
          server.config.logger.info(`  ➜  内部监听（非浏览器入口）: ${new URL(url).host}`);
        }
      };
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? '/', 'http://vite.local').pathname;
        if (pathname !== '/runtime-config.json') {
          next();
          return;
        }

        response.statusCode = 200;
        response.setHeader('Content-Type', 'application/json; charset=utf-8');
        response.setHeader('Cache-Control', 'no-store');
        response.end(JSON.stringify({ schemaVersion: 1, apiBaseUrl: DEVELOPMENT_API_ORIGIN }));
      });
    },
  };
}

export default defineConfig({
  plugins: [
    controlledDevelopmentRuntimeConfig(),
    vue(),
    UnoCSS({
      // 首屏即包含异步页面样式；使用 inline 扫描避免 UnoCSS 66 的独立文件 watcher 无法随测试服务器关闭。
      content: {
        inline: [
          () =>
            [new URL('./src/', import.meta.url), new URL('../shared/admin/src/', import.meta.url)]
              .flatMap((directory) =>
                [...globSync('**/*.{vue,ts}', { cwd: fileURLToPath(directory) })].map((file) =>
                  readFileSync(new URL(file, directory), 'utf8'),
                ),
              )
              .join('\n'),
        ],
      },
      presets: [presetWind3()],
      shortcuts: {
        'flex-center': 'flex items-center justify-center',
        'flex-y-center': 'flex items-center',
        'i-flex-col': 'inline-flex flex-col',
        'flex-col-stretch': 'flex flex-col items-stretch',
      },
    }),
  ],
  server: {
    // 正式本地入口只允许经共享 HTTPS Edge 访问，不能把 Vite 暴露到 LAN。
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    allowedHosts: ['platform.saas.forge.test'],
    hmr: {
      protocol: 'wss',
      host: 'platform.saas.forge.test',
      clientPort: 443,
    },
  },
});
