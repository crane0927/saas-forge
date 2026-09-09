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

const tenantRoot = fileURLToPath(new URL('../tenant-console-shell/', import.meta.url));
const tenantApp = fileURLToPath(new URL('../tenant-console-shell/src/app.tsx', import.meta.url));
const tenantRoutes = fileURLToPath(
  new URL('../tenant-console-shell/src/routes.tsx', import.meta.url),
);
const remote = fileURLToPath(
  new URL('../business-remotes/design-system-consumer-fixture/src/remote.tsx', import.meta.url),
);
const staticRemoteAcceptance = fileURLToPath(
  new URL('../tenant-console-shell/src/static-remote-acceptance.tsx', import.meta.url),
);
const acceptanceRoutes = '\0acceptance-tenant-routes';
const acceptanceApp = '\0acceptance-tenant-app';
const acceptanceRuntime = '\0acceptance-tenant-runtime';
const runtimeEntry = fileURLToPath(new URL('../shared/app-runtime/src/index.ts', import.meta.url));
const tenantMain = fileURLToPath(new URL('../tenant-console-shell/src/main.tsx', import.meta.url));

// 仅替换验收构建的路由组合，复用真实 Tenant App、认证 Runtime 与共享 Shell。
// 静态消费夹具验证主题继承，不代表 Module Federation 或网络 Remote 加载。
await build({
  configFile: fileURLToPath(new URL('../tenant-console-shell/vite.config.ts', import.meta.url)),
  root: tenantRoot,
  mode: 'static-acceptance',
  plugins: [
    {
      name: 'acceptance-brand-remote-route',
      enforce: 'pre',
      resolveId(source, importer) {
        if (source === './routes' && importer === tenantApp) return acceptanceRoutes;
        if (source === './app' && importer === tenantMain) return acceptanceApp;
        if (source === '@saas-forge/app-runtime' && importer === tenantApp)
          return acceptanceRuntime;
      },
      load(id) {
        if (id === acceptanceRuntime)
          return `
          export * from ${JSON.stringify(runtimeEntry)};
          import { createAuthenticationRuntimeAfterConfig as createRuntime } from ${JSON.stringify(runtimeEntry)};
          export function createAuthenticationRuntimeAfterConfig(config, options) {
            const result = createRuntime(config, options);
            if (result.ok) globalThis.acceptanceRetryContext = () => result.runtime.retryRecovery().then(value => value.ok);
            return result;
          }
        `;
        if (id === acceptanceApp)
          return `
          import { createElement } from 'react';
          import { TenantConsoleShellApp as App } from ${JSON.stringify(tenantApp)};
          export function TenantConsoleShellApp(props) {
            return createElement(App, { ...props, onBrandRejected(reason) {
              (globalThis.acceptanceBrandReasons ??= []).push(reason);
            }});
          }
        `;
        if (id !== acceptanceRoutes) return;
        return `
        import { createElement } from 'react';
        import { createTenantAuthenticationRoutes as baseRoutes } from ${JSON.stringify(tenantRoutes)};
        import { DesignSystemConsumerRemote } from ${JSON.stringify(remote)};
        import StaticRemoteAcceptance from ${JSON.stringify(staticRemoteAcceptance)};
        export function createTenantAuthenticationRoutes(locale) {
          return [...baseRoutes(locale), {
            path: '/acceptance/brand-remote', label: 'Remote acceptance',
            element: createElement('div', { 'data-testid': 'brand-remote' }, createElement(DesignSystemConsumerRemote, { locale })),
          }, {
            path: '/acceptance/static-remote', label: 'Static Remote acceptance',
            element: createElement(StaticRemoteAcceptance),
          }];
        }
      `;
      },
    },
  ],
  build: {
    outDir: fileURLToPath(new URL('../integration-test/dist/tenant-console', import.meta.url)),
    emptyOutDir: true,
  },
});
