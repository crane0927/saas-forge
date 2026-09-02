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
  type TenantBrandProfile,
} from '@saas-forge/design-system';
import { AuthenticationShell } from '@saas-forge/react-shell';
import {
  useEffect,
  useState,
  useSyncExternalStore,
  type ComponentType,
  type ReactNode,
} from 'react';
import { BrowserRouter } from 'react-router';

import { tenantAuthenticationRoutes } from './routes';

interface TenantConsoleShellAppProps {
  readonly bootstrap?: RuntimeConfigBootstrap;
  readonly authenticationFetch?: AuthenticationFetch;
  readonly realm?: object;
  readonly root: ComponentType<TenantConsoleRootProps>;
}

export interface TenantConsoleRootProps {
  readonly children: ReactNode;
  readonly tenantBrand?: TenantBrandProfile;
}

const defaultBootstrap = createRuntimeConfigBootstrap();
const defaultRealm = {};
const defaultAuthenticationFetch: AuthenticationFetch = (input, init) => fetch(input, init);

export function TenantConsoleShellApp({
  bootstrap = defaultBootstrap,
  authenticationFetch = defaultAuthenticationFetch,
  realm = defaultRealm,
  root,
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
      root={root}
    />
  );
}

function BootstrapSurface({
  bootstrap,
  state,
  authenticationFetch,
  realm,
  root: Root,
}: {
  readonly bootstrap: RuntimeConfigBootstrap;
  readonly state: BootstrapState;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
  readonly root: ComponentType<TenantConsoleRootProps>;
}) {
  if (state.status === 'ready') {
    return (
      <BrowserRouter>
        <TenantAuthenticationPath
          config={state.config}
          authenticationFetch={authenticationFetch}
          realm={realm}
          root={Root}
        />
      </BrowserRouter>
    );
  }

  if (state.status === 'failed') {
    return (
      <Root>
        <ConfigurationFailure
          applicationName="Tenant Console"
          errorCode={state.error.code}
          onRetry={() => void bootstrap.retry()}
        />
      </Root>
    );
  }

  return (
    <Root>
      <ApplicationLoading applicationName="Tenant Console" />
    </Root>
  );
}

function TenantAuthenticationPath({
  config,
  authenticationFetch,
  realm,
  root: Root,
}: {
  readonly config: RuntimeConfig;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
  readonly root: ComponentType<TenantConsoleRootProps>;
}) {
  const [runtimeResult] = useState(() =>
    createAuthenticationRuntimeAfterConfig(
      { ok: true, config },
      { realm, intent: 'TENANT', fetch: authenticationFetch },
    ),
  );
  if (!runtimeResult.ok) {
    return (
      <Root>
        <ConfigurationFailure
          applicationName="Tenant Console"
          errorCode={runtimeResult.error.code}
          onRetry={() => {
            window.location.reload();
          }}
        />
      </Root>
    );
  }
  return <TenantRuntimeSurface runtime={runtimeResult.runtime} root={Root} />;
}

function TenantRuntimeSurface({
  runtime,
  root: Root,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly root: ComponentType<TenantConsoleRootProps>;
}) {
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
    <Root tenantBrand={brandProfile}>
      <AuthenticationShell
        applicationName={
          brandProfile?.displayName ?? tenantContext?.tenantDisplayName ?? 'Tenant Console'
        }
        runtime={runtime}
        defaultPath="/"
        routes={tenantAuthenticationRoutes}
      />
    </Root>
  );
}
