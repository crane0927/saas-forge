import { DesignSystemProvider } from '@saas-forge/design-system';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { DesignSystemConsumerRemote } from '../src/remote';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Design System Remote fixture root element is missing.');
}

createRoot(rootElement).render(
  <StrictMode>
    <DesignSystemProvider>
      <DesignSystemConsumerRemote />
    </DesignSystemProvider>
  </StrictMode>,
);
