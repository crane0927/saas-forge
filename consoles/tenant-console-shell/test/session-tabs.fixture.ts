import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { createApp, h, provide, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ElConfigProvider } from 'element-plus';
import zhCN from 'element-plus/es/locale/lang/zh-cn';
import { consoleContextKey } from '../../shared/admin/src/runtime/context';
import Session from '../../shared/admin/src/application/Session.vue';
import '@saas-forge/admin/styles.css';
import 'virtual:uno.css';
let now = Date.now();
const result = createAuthenticationRuntimeAfterConfig(
  { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
  {
    realm: globalThis,
    intent: new URLSearchParams(location.search).get('slot') === 'PLATFORM' ? 'PLATFORM' : 'TENANT',
    fetch: globalThis.fetch.bind(globalThis),
    now: () => now,
  },
);
if (!result.ok) throw new Error('Invalid acceptance configuration');
const runtime = result.runtime;
// 浏览器验收仅通过 Runtime 公共接口控制时钟及操作；不暴露内存 Token。
Object.assign(window, {
  sessionAcceptance: {
    advance: () => {
      now += 100_000;
    },
    state: () => runtime.getState(),
    read: () => runtime.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' }),
    logout: () => runtime.logout(),
    switchTenant: () =>
      runtime.switchTenantContext({ membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6079' }),
  },
});

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    {
      path: '/',
      name: 'workspace',
      meta: { business: true },
      component: { render: () => h('h1', '受保护的工作台') },
    },
    { path: '/:pathMatch(.*)*', component: { render: () => null } },
  ],
});
createApp({
  setup() {
    provide(consoleContextKey, {
      runtime,
      locale: ref('zh-CN'),
      guards: new Map(),
      requestExit: async () => true,
    });
    return () => h(ElConfigProvider, { locale: zhCN }, () => h(Session, { navigation: [] }));
  },
})
  .use(router)
  .mount('#app');
