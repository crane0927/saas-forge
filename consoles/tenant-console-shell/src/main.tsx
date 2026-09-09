import {
  AuthenticationRootErrorBoundary,
  BrandApplicationProvider,
  ConsoleLocaleProvider,
  ConsoleLocaleSelector,
  resolveInitialConsoleLocale,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { lazy, Suspense } from 'react';
import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp, type TenantConsoleRootProps } from './app';

function TenantConsoleRoot({ children, resolvedBrand }: TenantConsoleRootProps) {
  const { locale } = useConsoleLocale();
  return (
    <BrandApplicationProvider resolvedBrand={resolvedBrand} surface="tenant" locale={locale}>
      <AuthenticationRootErrorBoundary applicationName="SaaS Forge" locale={locale}>
        <ConsoleLocaleSelector />
        {children}
      </AuthenticationRootErrorBoundary>
    </BrandApplicationProvider>
  );
}

const StaticRemoteAcceptance =
  import.meta.env.DEV || import.meta.env.MODE === 'static-acceptance'
    ? lazy(() => import('./static-remote-acceptance'))
    : null;

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Tenant Console Shell root element is missing.');
}
const initialLocale = resolveInitialConsoleLocale();
document.documentElement.lang = initialLocale;

createRoot(rootElement, {
  // 已捕获故障由安全界面呈现；生产日志不得输出 React 默认记录的原始 Error。
  onCaughtError: import.meta.env.PROD ? () => undefined : undefined,
}).render(
  <ConsoleLocaleProvider>
    <TenantConsoleEntry />
  </ConsoleLocaleProvider>,
);

function TenantConsoleEntry() {
  if (StaticRemoteAcceptance !== null && location.pathname === '/acceptance/static-remote') {
    return (
      <TenantConsoleRoot resolvedBrand={platformResolvedBrandProfile}>
        <Suspense fallback={<p role="status">Loading acceptance entry</p>}>
          <StaticRemoteAcceptance />
        </Suspense>
      </TenantConsoleRoot>
    );
  }
  return <TenantConsoleShellApp root={TenantConsoleRoot} />;
}
