import { DesignSystemProvider } from '@saas-forge/design-system';
import {
  AuthenticationRootErrorBoundary,
  ConsoleLocaleProvider,
  resolveInitialConsoleLocale,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp, type TenantConsoleRootProps } from './app';

function TenantConsoleRoot({ children, tenantBrand }: TenantConsoleRootProps) {
  const { locale } = useConsoleLocale();
  return (
    <DesignSystemProvider locale={locale} tenantBrand={tenantBrand}>
      {children}
    </DesignSystemProvider>
  );
}

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
  <ConsoleLocaleProvider initialLocale={initialLocale}>
    <TenantConsoleEntry />
  </ConsoleLocaleProvider>,
);

function TenantConsoleEntry() {
  const { locale } = useConsoleLocale();

  return (
    <AuthenticationRootErrorBoundary applicationName="Tenant Console" locale={locale}>
      <TenantConsoleShellApp root={TenantConsoleRoot} />
    </AuthenticationRootErrorBoundary>
  );
}
