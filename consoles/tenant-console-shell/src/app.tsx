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
  platformResolvedBrandProfile,
  resolveBrandProfile,
  type BrandAssetPreloader,
  type BrandRejectionReasonCode,
  type ResolvedBrandProfile,
} from '@saas-forge/design-system';
import {
  AuthenticationShell,
  BrandApplicationLoading,
  BrandConfigurationFailure,
  useConsoleLocale,
} from '@saas-forge/react-shell';
import {
  useEffect,
  useEffectEvent,
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
  readonly brandAssetPreloader?: BrandAssetPreloader;
  readonly onBrandRejected?: (reason: BrandRejectionReasonCode) => void;
  readonly root: ComponentType<TenantConsoleRootProps>;
}

export interface TenantConsoleRootProps {
  readonly children: ReactNode;
  readonly resolvedBrand: ResolvedBrandProfile;
}

const defaultBootstrap = createRuntimeConfigBootstrap();
// Realm 同时提供原生会话协调能力；空对象会让生产入口始终退回 IAM Lease。
const defaultRealm = globalThis;
const defaultAuthenticationFetch: AuthenticationFetch = (input, init) => fetch(input, init);

export function TenantConsoleShellApp({
  bootstrap = defaultBootstrap,
  authenticationFetch = defaultAuthenticationFetch,
  realm = defaultRealm,
  brandAssetPreloader,
  onBrandRejected,
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
      brandAssetPreloader={brandAssetPreloader}
      onBrandRejected={onBrandRejected}
      root={root}
    />
  );
}

function BootstrapSurface({
  bootstrap,
  state,
  authenticationFetch,
  realm,
  brandAssetPreloader,
  onBrandRejected,
  root: Root,
}: {
  readonly bootstrap: RuntimeConfigBootstrap;
  readonly state: BootstrapState;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
  readonly brandAssetPreloader?: BrandAssetPreloader;
  readonly onBrandRejected?: (reason: BrandRejectionReasonCode) => void;
  readonly root: ComponentType<TenantConsoleRootProps>;
}) {
  if (state.status === 'ready') {
    return (
      <BrowserRouter>
        <TenantAuthenticationPath
          config={state.config}
          authenticationFetch={authenticationFetch}
          realm={realm}
          brandAssetPreloader={brandAssetPreloader}
          onBrandRejected={onBrandRejected}
          root={Root}
        />
      </BrowserRouter>
    );
  }

  if (state.status === 'failed') {
    return (
      <Root resolvedBrand={platformResolvedBrandProfile}>
        <BrandConfigurationFailure
          errorCode={state.error.code}
          onRetry={() => void bootstrap.retry()}
        />
      </Root>
    );
  }

  return (
    <Root resolvedBrand={platformResolvedBrandProfile}>
      <BrandApplicationLoading />
    </Root>
  );
}

function TenantAuthenticationPath({
  config,
  authenticationFetch,
  realm,
  brandAssetPreloader,
  onBrandRejected,
  root: Root,
}: {
  readonly config: RuntimeConfig;
  readonly authenticationFetch: AuthenticationFetch;
  readonly realm: object;
  readonly brandAssetPreloader?: BrandAssetPreloader;
  readonly onBrandRejected?: (reason: BrandRejectionReasonCode) => void;
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
      <Root resolvedBrand={platformResolvedBrandProfile}>
        <BrandConfigurationFailure
          errorCode={runtimeResult.error.code}
          onRetry={() => {
            window.location.reload();
          }}
        />
      </Root>
    );
  }
  return (
    <TenantRuntimeSurface
      runtime={runtimeResult.runtime}
      brandAssetPreloader={brandAssetPreloader}
      onBrandRejected={onBrandRejected}
      root={Root}
    />
  );
}

function TenantRuntimeSurface({
  runtime,
  brandAssetPreloader,
  onBrandRejected,
  root: Root,
}: {
  readonly runtime: AuthenticationRuntime;
  readonly brandAssetPreloader?: BrandAssetPreloader;
  readonly onBrandRejected?: (reason: BrandRejectionReasonCode) => void;
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
  const [resolved, setResolved] = useState<{
    readonly input: typeof brandProfile;
    readonly brand: ResolvedBrandProfile;
  }>();
  const reportBrandRejection = useEffectEvent((reason: BrandRejectionReasonCode) => {
    onBrandRejected?.(reason);
  });

  useEffect(() => {
    if (brandProfile === undefined) return;
    const controller = new AbortController();
    let current = true;
    void resolveBrandProfile(brandProfile, {
      signal: controller.signal,
      isCurrent: () => current,
      onRejected: (reason) => {
        if (current) reportBrandRejection(reason);
      },
      ...(brandAssetPreloader === undefined ? {} : { preloadAsset: brandAssetPreloader }),
    }).then((resolution) => {
      if (!current) return;
      setResolved({ input: brandProfile, brand: resolution.resolvedBrand });
    });
    return () => {
      current = false;
      controller.abort();
    };
  }, [brandAssetPreloader, brandProfile]);

  const resolutionPending = brandProfile !== undefined && resolved?.input !== brandProfile;
  const resolvedBrand =
    brandProfile === undefined || resolutionPending
      ? platformResolvedBrandProfile
      : (resolved?.brand ?? platformResolvedBrandProfile);

  return (
    <Root resolvedBrand={resolvedBrand}>
      <AuthenticationShell runtime={runtime} defaultPath="/" routes={routes} />
    </Root>
  );
}
