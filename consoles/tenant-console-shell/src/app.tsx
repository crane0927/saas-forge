import {
  createAuthenticationRuntimeAfterConfig,
  createRuntimeConfigBootstrap,
  type AuthenticationFetch,
  type AuthenticationRuntime,
  type BootstrapState,
  type RuntimeConfig,
  type RuntimeConfigBootstrap,
} from '@saas-forge/app-runtime';
import {
  ApplicationLoading,
  ConfigurationFailure,
  DesignSystemProvider,
} from '@saas-forge/design-system';
import { AuthenticationShell } from '@saas-forge/react-shell';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { BrowserRouter } from 'react-router';

import { tenantAuthenticationRoutes } from './routes';

interface TenantConsoleShellAppProps {
  readonly bootstrap?: RuntimeConfigBootstrap;
  readonly authenticationFetch?: AuthenticationFetch;
  readonly realm?: object;
}

const defaultBootstrap = createRuntimeConfigBootstrap();
const defaultRealm = {};
const defaultAuthenticationFetch: AuthenticationFetch = (input, init) => fetch(input, init);

export function TenantConsoleShellApp({
  bootstrap = defaultBootstrap,
  authenticationFetch = defaultAuthenticationFetch,
  realm = defaultRealm,
}: TenantConsoleShellAppProps) {
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
        <TenantAuthenticationPath
          config={state.config}
          authenticationFetch={authenticationFetch}
          realm={realm}
        />
      </BrowserRouter>
    );
  }

  if (state.status === 'failed') {
    return (
      <DesignSystemProvider>
        <ConfigurationFailure
          applicationName="Tenant Console"
          errorCode={state.error.code}
          onRetry={() => void bootstrap.retry()}
        />
      </DesignSystemProvider>
    );
  }

  return (
    <DesignSystemProvider>
      <ApplicationLoading applicationName="Tenant Console" />
    </DesignSystemProvider>
  );
}

function TenantAuthenticationPath({
  config,
  authenticationFetch,
  realm,
}: {
  readonly config: RuntimeConfig;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
}) {
  const [runtimeResult] = useState(() =>
    createAuthenticationRuntimeAfterConfig(
      { ok: true, config },
      { realm, intent: 'TENANT', fetch: authenticationFetch },
    ),
  );
  if (!runtimeResult.ok) {
    return (
      <DesignSystemProvider>
        <ConfigurationFailure
          applicationName="Tenant Console"
          errorCode={runtimeResult.error.code}
          onRetry={() => {
            window.location.reload();
          }}
        />
      </DesignSystemProvider>
    );
  }
  return <TenantRuntimeSurface runtime={runtimeResult.runtime} />;
}

function TenantRuntimeSurface({ runtime }: { readonly runtime: AuthenticationRuntime }) {
  const state = useSyncExternalStore(
    (listener) => runtime.subscribe(listener),
    () => runtime.getState(),
  );
  const tenantContext = state.status === 'authenticated' ? state.tenantContext : undefined;
  const brandProfile = tenantContext?.brandProfile;

  useEffect(() => {
    const icon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
    if (icon === null) return;
    const originalHref = icon.getAttribute('href');
    if (brandProfile?.faviconUrl === undefined) {
      if (originalHref === null) icon.removeAttribute('href');
      return;
    }
    icon.setAttribute('href', brandProfile.faviconUrl);
    return () => {
      if (originalHref === null) icon.removeAttribute('href');
      else icon.setAttribute('href', originalHref);
    };
  }, [brandProfile?.faviconUrl]);

  return (
    <DesignSystemProvider tenantBrand={brandProfile}>
      <AuthenticationShell
        applicationName={
          brandProfile?.displayName ?? tenantContext?.tenantDisplayName ?? 'Tenant Console'
        }
        runtime={runtime}
        defaultPath="/"
        routes={tenantAuthenticationRoutes}
      />
    </DesignSystemProvider>
  );
}
