import { DesignSystemProvider } from '@saas-forge/design-system';
import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp } from './app';
import { RootErrorBoundary } from './error-boundary';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Tenant Console Shell root element is missing.');
}

createRoot(rootElement).render(
  <DesignSystemProvider>
    <RootErrorBoundary>
      <TenantConsoleShellApp />
    </RootErrorBoundary>
  </DesignSystemProvider>,
);
