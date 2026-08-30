import '@saas-forge/app-runtime/bootstrap.css';

import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp } from './app';
import { RootErrorBoundary } from './error-boundary';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Tenant Console Shell root element is missing.');
}

createRoot(rootElement).render(
  <RootErrorBoundary>
    <TenantConsoleShellApp />
  </RootErrorBoundary>,
);
