import { AuthenticationRootErrorBoundary } from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp } from './app';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Tenant Console Shell root element is missing.');
}

createRoot(rootElement).render(
  <AuthenticationRootErrorBoundary applicationName="Tenant Console">
    <TenantConsoleShellApp />
  </AuthenticationRootErrorBoundary>,
);
