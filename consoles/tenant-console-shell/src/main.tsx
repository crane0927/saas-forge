import { DesignSystemProvider } from '@saas-forge/design-system';
import { AuthenticationRootErrorBoundary } from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { TenantConsoleShellApp, type TenantConsoleRootProps } from './app';

function TenantConsoleRoot({ children, tenantBrand }: TenantConsoleRootProps) {
  return <DesignSystemProvider tenantBrand={tenantBrand}>{children}</DesignSystemProvider>;
}

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Tenant Console Shell root element is missing.');
}

createRoot(rootElement).render(
  <AuthenticationRootErrorBoundary applicationName="Tenant Console">
    <TenantConsoleShellApp root={TenantConsoleRoot} />
  </AuthenticationRootErrorBoundary>,
);
