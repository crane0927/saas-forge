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
const tenantMain = fileURLToPath(new URL('../tenant-console-shell/src/main.ts', import.meta.url));
const application = fileURLToPath(
  new URL('../shared/admin/src/application/ConsoleApplication.vue', import.meta.url),
);
const runtimeEntry = fileURLToPath(new URL('../shared/app-runtime/src/index.ts', import.meta.url));
const remote = fileURLToPath(
  new URL('../tenant-console-shell/test/BrandRemoteRoute.vue', import.meta.url),
);
const acceptanceRuntime = '\0acceptance-runtime';
// 只在验收构建注入公开 Runtime 重试入口及静态 Remote 路由。
await build({
  configFile: fileURLToPath(new URL('../tenant-console-shell/vite.config.ts', import.meta.url)),
  root: tenantRoot,
  mode: 'static-acceptance',
  plugins: [
    {
      name: 'acceptance-routes',
      enforce: 'pre',
      resolveId(source, importer) {
        if (source === '@saas-forge/app-runtime' && importer?.split('?')[0] === application)
          return acceptanceRuntime;
      },
      load(id) {
        if (id === acceptanceRuntime)
          return `export * from ${JSON.stringify(runtimeEntry)};import {createAuthenticationRuntimeAfterConfig as create} from ${JSON.stringify(runtimeEntry)};export function createAuthenticationRuntimeAfterConfig(config,options){const result=create(config,options);if(result.ok)globalThis.acceptanceRetryContext=()=>result.runtime.retryRecovery().then(value=>value.ok);return result;}`;
      },
      transform(source, id) {
        if (id === tenantMain)
          return source
            .replace(
              /routes:\s*\[/,
              `routes:[{path:'/acceptance/brand-remote',name:'brand-remote',meta:{business:true},component:()=>import(${JSON.stringify(remote)}),props:()=>({locale:document.documentElement.lang})},`,
            )
            .replace(
              /navigation:\s*\(?locale\)?\s*=>\s*\[/,
              "navigation:locale=>[{path:'/acceptance/brand-remote',label:'Remote acceptance',icon:HomeFilled},",
            );
        if (id.endsWith('/brand/resolved-brand.ts'))
          return source.replace(
            'options.onRejected?.(reason);',
            'options.onRejected?.(reason); (globalThis.acceptanceBrandReasons ??= []).push(reason);',
          );
      },
    },
  ],
  build: {
    outDir: fileURLToPath(new URL('../integration-test/dist/tenant-console', import.meta.url)),
    emptyOutDir: true,
  },
});
