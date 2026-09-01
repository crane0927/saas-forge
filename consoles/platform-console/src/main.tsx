import { DesignSystemProvider } from '@saas-forge/design-system';
import { AuthenticationRootErrorBoundary } from '@saas-forge/react-shell';
import { createRoot } from 'react-dom/client';

import { PlatformConsoleApp } from './app';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Platform Console root element is missing.');
}

createRoot(rootElement).render(
  <DesignSystemProvider>
    <AuthenticationRootErrorBoundary applicationName="Platform Console">
      <PlatformConsoleApp />
    </AuthenticationRootErrorBoundary>
  </DesignSystemProvider>,
);
