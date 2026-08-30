import {
  createRuntimeConfigBootstrap,
  type BootstrapState,
  type RuntimeConfigBootstrap,
} from '@saas-forge/app-runtime';
import { ApplicationLoading, ConfigurationFailure } from '@saas-forge/design-system';
import { useEffect, useSyncExternalStore } from 'react';
import { BrowserRouter } from 'react-router';

import { PlatformRoutes } from './routes';

interface PlatformConsoleAppProps {
  readonly bootstrap?: RuntimeConfigBootstrap;
}

const defaultBootstrap = createRuntimeConfigBootstrap();

export function PlatformConsoleApp({ bootstrap = defaultBootstrap }: PlatformConsoleAppProps) {
  const state = useSyncExternalStore(
    (listener) => bootstrap.subscribe(listener),
    () => bootstrap.getState(),
  );

  useEffect(() => {
    void bootstrap.start();
  }, [bootstrap]);

  return <BootstrapSurface bootstrap={bootstrap} state={state} />;
}

function BootstrapSurface({
  bootstrap,
  state,
}: {
  readonly bootstrap: RuntimeConfigBootstrap;
  readonly state: BootstrapState;
}) {
  if (state.status === 'ready') {
    return (
      <BrowserRouter>
        <PlatformRoutes />
      </BrowserRouter>
    );
  }

  if (state.status === 'failed') {
    return (
      <ConfigurationFailure
        applicationName="Platform Console"
        errorCode={state.error.code}
        onRetry={() => void bootstrap.retry()}
      />
    );
  }

  return <ApplicationLoading applicationName="Platform Console" />;
}
