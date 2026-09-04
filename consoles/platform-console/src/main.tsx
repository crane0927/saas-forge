import { DesignSystemProvider } from '@saas-forge/design-system';
import {
  AuthenticationRootErrorBoundary,
  ConsoleLocaleProvider,
  resolveInitialConsoleLocale,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { PlatformConsoleApp } from './app';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Platform Console root element is missing.');
}
const initialLocale = resolveInitialConsoleLocale();
document.documentElement.lang = initialLocale;

createRoot(rootElement, {
  // 已捕获故障由安全界面呈现；生产日志不得输出 React 默认记录的原始 Error。
  onCaughtError: import.meta.env.PROD ? () => undefined : undefined,
}).render(
  <ConsoleLocaleProvider initialLocale={initialLocale}>
    <PlatformConsoleEntry />
  </ConsoleLocaleProvider>,
);

function PlatformConsoleEntry() {
  const { locale } = useConsoleLocale();

  return (
    <DesignSystemProvider locale={locale}>
      <AuthenticationRootErrorBoundary applicationName="Platform Console" locale={locale}>
        <PlatformConsoleApp />
      </AuthenticationRootErrorBoundary>
    </DesignSystemProvider>
  );
}
