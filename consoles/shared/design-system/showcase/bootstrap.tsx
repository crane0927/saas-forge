import { DesignSystemProvider } from '@saas-forge/design-system';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { DesignSystemShowcase } from './main';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('Design System 展示入口缺少根元素。');
}

createRoot(root).render(
  <StrictMode>
    <DesignSystemProvider>
      <DesignSystemShowcase />
    </DesignSystemProvider>
  </StrictMode>,
);
