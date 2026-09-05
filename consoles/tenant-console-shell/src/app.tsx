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
import { AuthenticationShell, useConsoleLocale } from '@saas-forge/react-shell';
import {
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ComponentType,
  type ReactNode,
} from 'react';
import { BrowserRouter } from 'react-router';

import { createTenantAuthenticationRoutes } from './routes';

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
// Realm 同时提供原生会话协调能力；空对象会让生产入口始终退回 IAM Lease。
const defaultRealm = globalThis;
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
  const { locale } = useConsoleLocale();
  const routes = useMemo(() => createTenantAuthenticationRoutes(locale), [locale]);
  const state = useSyncExternalStore(
    (listener) => runtime.subscribe(listener),
    () => runtime.getState(),
  );
  const tenantContext = state.status === 'authenticated' ? state.tenantContext : undefined;
  const brandProfile = tenantContext?.brandProfile;

  useEffect(() => {
    if (brandProfile?.faviconUrl === undefined) return;
    const existingIcon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
    const icon = existingIcon ?? document.createElement('link');
    const originalHref = icon.getAttribute('href');
    icon.setAttribute('href', brandProfile.faviconUrl);
    // 默认生产 HTML 没有图标；仅在会话携带品牌时创建，退出或切换时恢复宿主原状。
    if (existingIcon === null) {
      icon.rel = 'icon';
      document.head.append(icon);
    }
    return () => {
      if (existingIcon === null) icon.remove();
      else if (originalHref === null) icon.removeAttribute('href');
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
        routes={routes}
      />
    </Root>
  );
}
