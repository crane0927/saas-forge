import {
  createAuthenticationRuntimeAfterConfig,
  createRuntimeConfigBootstrap,
  type AuthenticationFetch,
  type BootstrapState,
  type RuntimeConfig,
  type RuntimeConfigBootstrap,
} from '@saas-forge/app-runtime';
import {
  AuthenticationShell,
  BrandApplicationLoading,
  BrandConfigurationFailure,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { BrowserRouter } from 'react-router';

import { createPlatformAuthenticationRoutes } from './routes';

interface PlatformConsoleAppProps {
  readonly bootstrap?: RuntimeConfigBootstrap;
  readonly authenticationFetch?: AuthenticationFetch;
  readonly realm?: object;
}

const defaultBootstrap = createRuntimeConfigBootstrap();
// Realm 同时提供原生会话协调能力；空对象会让生产入口始终退回 IAM Lease。
const defaultRealm = globalThis;
const defaultAuthenticationFetch: AuthenticationFetch = (input, init) => fetch(input, init);

export function PlatformConsoleApp({
  bootstrap = defaultBootstrap,
  authenticationFetch = defaultAuthenticationFetch,
  realm = defaultRealm,
}: PlatformConsoleAppProps) {
  const state = useSyncExternalStore(
    (listener) => bootstrap.subscribe(listener),
    () => bootstrap.getState(),
  );

  useEffect(() => {
    void bootstrap.start();
  }, [bootstrap]);

  return (
    <BootstrapSurface
      bootstrap={bootstrap}
      state={state}
      authenticationFetch={authenticationFetch}
      realm={realm}
    />
  );
}

function BootstrapSurface({
  bootstrap,
  state,
  authenticationFetch,
  realm,
}: {
  readonly bootstrap: RuntimeConfigBootstrap;
  readonly state: BootstrapState;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
}) {
  if (state.status === 'ready') {
    return (
      <BrowserRouter>
        <PlatformAuthenticationPath
          config={state.config}
          authenticationFetch={authenticationFetch}
          realm={realm}
        />
      </BrowserRouter>
    );
  }

  if (state.status === 'failed') {
    return (
      <BrandConfigurationFailure
        errorCode={state.error.code}
        onRetry={() => void bootstrap.retry()}
      />
    );
  }

  return <BrandApplicationLoading />;
}

function PlatformAuthenticationPath({
  config,
  authenticationFetch,
  realm,
}: {
  readonly config: RuntimeConfig;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
}) {
  const { locale } = useConsoleLocale();
  const [runtimeResult] = useState(() =>
    createAuthenticationRuntimeAfterConfig(
      { ok: true, config },
      { realm, intent: 'PLATFORM', fetch: authenticationFetch },
    ),
  );
  if (!runtimeResult.ok) {
    return (
      <BrandConfigurationFailure
        errorCode={runtimeResult.error.code}
        onRetry={() => {
          window.location.reload();
        }}
      />
    );
  }
  return (
    <AuthenticationShell
      runtime={runtimeResult.runtime}
      defaultPath="/"
      routes={createPlatformAuthenticationRoutes(locale, runtimeResult.runtime.client)}
    />
  );
}
