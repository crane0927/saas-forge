import { DesignSystemProvider } from '@saas-forge/design-system';
import { AuthenticationRootErrorBoundary } from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { PlatformConsoleApp } from './app';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Platform Console root element is missing.');
}

createRoot(rootElement, {
  // 已捕获故障由安全界面呈现；生产日志不得输出 React 默认记录的原始 Error。
  onCaughtError: import.meta.env.PROD ? () => undefined : undefined,
}).render(
  <DesignSystemProvider>
    <AuthenticationRootErrorBoundary applicationName="Platform Console">
      <PlatformConsoleApp />
    </AuthenticationRootErrorBoundary>
  </DesignSystemProvider>,
);
