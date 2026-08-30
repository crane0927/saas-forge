import {
  createRuntimeConfigBootstrap,
  type BootstrapState,
  type RuntimeConfigBootstrap,
} from '@saas-forge/app-runtime';
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
      <main className="sf-runtime-surface">
        <section className="sf-runtime-panel" aria-labelledby="config-error-title">
          <h1 id="config-error-title">Platform Console 配置不可用</h1>
          <p>部署配置未能通过校验。请确认配置已就绪后重试。</p>
          <p className="sf-runtime-code">{state.error.code}</p>
          <button
            className="sf-runtime-action"
            type="button"
            onClick={() => void bootstrap.retry()}
          >
            重试
          </button>
        </section>
      </main>
    );
  }

  return (
    <main className="sf-runtime-surface" aria-busy="true" aria-live="polite">
      <section className="sf-runtime-panel" aria-labelledby="loading-title">
        <h1 id="loading-title">正在启动 Platform Console</h1>
        <p>正在加载部署配置。</p>
      </section>
    </main>
  );
}
