import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

const DEVELOPMENT_API_ORIGIN = 'https://api.saasforge.test';

function controlledDevelopmentRuntimeConfig(): Plugin {
  return {
    name: 'controlled-development-runtime-config',
    apply: 'serve',
    configureServer(server) {
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
  plugins: [controlledDevelopmentRuntimeConfig(), react()],
  server: {
    // 正式本地入口只允许经共享 HTTPS Edge 访问，不能把 Vite 暴露到 LAN。
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    allowedHosts: ['platform.saasforge.test'],
    hmr: {
      protocol: 'wss',
      host: 'platform.saasforge.test',
      clientPort: 443,
    },
  },
});
