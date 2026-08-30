import '@saas-forge/app-runtime/bootstrap.css';

import { createRoot } from 'react-dom/client';

import { PlatformConsoleApp } from './app';
import { RootErrorBoundary } from './error-boundary';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Platform Console root element is missing.');
}

createRoot(rootElement).render(
  <RootErrorBoundary>
    <PlatformConsoleApp />
  </RootErrorBoundary>,
);
