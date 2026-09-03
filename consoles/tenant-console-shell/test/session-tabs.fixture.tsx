import { createAuthenticationRuntimeAfterConfig } from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { AuthenticationShell } from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';

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
const root = document.getElementById('root');
if (root === null) throw new Error('Missing acceptance root');
createRoot(root).render(
  <DesignSystemProvider>
    <MemoryRouter>
      <AuthenticationShell
        applicationName="Tenant Console"
        runtime={runtime}
        defaultPath="/"
        routes={[{ path: '/', label: '工作台', element: <h1>受保护的工作台</h1> }]}
      />
    </MemoryRouter>
  </DesignSystemProvider>,
);
