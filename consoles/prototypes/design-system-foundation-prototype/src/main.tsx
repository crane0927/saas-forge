import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { DesignSystemFoundationPrototype } from './prototype';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('PROTOTYPE_ROOT_MISSING');
}

createRoot(root).render(
  <StrictMode>
    <DesignSystemFoundationPrototype />
  </StrictMode>,
);
