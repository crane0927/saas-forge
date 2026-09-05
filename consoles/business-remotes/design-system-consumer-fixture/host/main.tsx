import { DesignSystemProvider } from '@saas-forge/design-system';
import {
  ConsoleLocaleProvider,
  ConsoleLocaleSelector,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { DesignSystemConsumerRemote } from '../src/remote';

const rootElement = document.querySelector('#root');
if (rootElement === null) {
  throw new Error('Design System Remote fixture root element is missing.');
}

createRoot(rootElement).render(
  <StrictMode>
    <ConsoleLocaleProvider>
      <FixtureHost />
    </ConsoleLocaleProvider>
  </StrictMode>,
);

function FixtureHost() {
  const { locale } = useConsoleLocale();
  return (
    <DesignSystemProvider locale={locale}>
      <ConsoleLocaleSelector />
      <DesignSystemConsumerRemote locale={locale} />
    </DesignSystemProvider>
  );
}
