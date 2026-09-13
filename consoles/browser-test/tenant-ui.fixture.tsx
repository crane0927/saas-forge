import { createRuntimeConfigBootstrap, type AuthenticationFetch } from '../shared/app-runtime/src';
import { platformResolvedBrandProfile } from '../shared/design-system/src';
import {
  BrandApplicationProvider,
  ConsoleLocaleProvider,
  ConsoleLocaleSelector,
  useConsoleLocale,
} from '../shared/react-shell/src';
import { useState } from 'react';
import { PlatformConsoleApp } from '../platform-console/src/app';

/** 复用正式 Console/Runtime 的浏览器缝；仅 HTTP 响应由测试夹具提供，不代表后端验收。 */
export function TenantUiFixture({ locale = 'zh-CN' }: { readonly locale?: 'zh-CN' | 'en-US' }) {
  return (
    <ConsoleLocaleProvider initialLocale={locale}>
      <FixtureContent />
    </ConsoleLocaleProvider>
  );
}

function FixtureContent() {
  const { locale } = useConsoleLocale();
  const [fixture] = useState(() => {
    const names = ['云杉协作', '星河科技', '山岚制造', '海棠工作室', '远山数据', '青禾实验室'];
    const states = ['ACTIVE', 'ACTIVE', 'PENDING', 'SUSPENDED', 'ACTIVE', 'CLOSED'];
    const tenants = names.map((displayName, index) => ({
      id: `019535d9-0000-7000-8000-${String(index + 1).padStart(12, '0')}`,
      displayName,
      status: states[index],
      expiresAt: null,
      createdAt: '2026-09-11T00:00:00.000Z',
      updatedAt: '2026-09-11T00:00:00.000Z',
    }));
    const fetch: AuthenticationFetch = (input, init) => {
      const url = new URL(input instanceof Request ? input.url : input);
      if (url.pathname.endsWith('/refresh'))
        return Promise.resolve(
          Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'fixture-token',
            tokenType: 'Bearer',
            expiresIn: 120,
          }),
        );
      if (url.pathname.endsWith('/tenant-creations'))
        return Promise.resolve(Response.json({ items: [], nextCursor: null, hasMore: false }));
      if (url.pathname.endsWith('/administrator-initialization'))
        return Promise.resolve(
          Response.json({
            tenantId: tenants[0].id,
            initializationId: tenants[0].id,
            state: 'SUCCEEDED',
            canStart: false,
            canContinue: false,
            initialAdministratorMembershipId: tenants[0].id,
          }),
        );
      if (url.pathname.endsWith('/tenants') && init?.method !== 'POST') {
        const name = url.searchParams.get('name') ?? '';
        const status = url.searchParams.get('status');
        return Promise.resolve(
          Response.json({
            items: tenants.filter(
              (tenant) =>
                tenant.displayName.includes(name) && (status === null || tenant.status === status),
            ),
            nextCursor: null,
            hasMore: false,
          }),
        );
      }
      const tenant = tenants.find((item) => url.pathname.endsWith(`/tenants/${item.id}`));
      if (tenant !== undefined) return Promise.resolve(Response.json(tenant));
      return Promise.reject(new Error(`Unimplemented fixture request: ${url.pathname}`));
    };
    return {
      fetch,
      realm: {},
      bootstrap: createRuntimeConfigBootstrap(() =>
        Promise.resolve({
          ok: true,
          config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' },
        }),
      ),
    };
  });
  return (
    <BrandApplicationProvider
      resolvedBrand={platformResolvedBrandProfile}
      surface="platform"
      locale={locale}
    >
      <ConsoleLocaleSelector />
      <PlatformConsoleApp
        bootstrap={fixture.bootstrap}
        authenticationFetch={fixture.fetch}
        realm={fixture.realm}
      />
    </BrandApplicationProvider>
  );
}
