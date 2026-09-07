import {
  ApplicationLoading,
  ConfigurationFailure,
  DesignSystemProvider,
  type DesignSystemLocale,
  type ResolvedBrandProfile,
} from '@saas-forge/design-system';
import { createContext, useContext, useLayoutEffect, useMemo, type ReactNode } from 'react';

export type ConsoleBrandSurface = 'platform' | 'tenant';

export interface BrandApplicationProviderProps {
  readonly resolvedBrand: ResolvedBrandProfile | undefined;
  readonly surface: ConsoleBrandSurface;
  readonly locale?: DesignSystemLocale;
  readonly children: ReactNode;
}

export interface BrandApplicationContextValue {
  readonly resolvedBrand: ResolvedBrandProfile;
  readonly applicationName: string;
  readonly documentTitle: string;
  readonly logoAlt: string;
}

export const BrandApplicationContext = createContext<BrandApplicationContextValue | undefined>(
  undefined,
);

/**
 * 原子安装一份已经由 Design System 解析完成的品牌；调用方不能逐字段覆盖品牌表面。
 */
export function BrandApplicationProvider({
  resolvedBrand,
  surface,
  locale = 'zh-CN',
  children,
}: BrandApplicationProviderProps) {
  const requiredBrand = requireResolvedBrand(resolvedBrand);
  const value = useMemo<BrandApplicationContextValue>(() => {
    const applicationName = requiredBrand.profile.displayName;
    return Object.freeze({
      resolvedBrand: requiredBrand,
      applicationName,
      documentTitle: brandApplicationTitle(requiredBrand, surface),
      logoAlt: `${applicationName} Logo`,
    });
  }, [requiredBrand, surface]);

  useLayoutEffect(() => applyDocumentBrand(value), [value]);

  return (
    <BrandApplicationContext.Provider value={value}>
      <DesignSystemProvider locale={locale} resolvedBrand={requiredBrand}>
        {children}
      </DesignSystemProvider>
    </BrandApplicationContext.Provider>
  );
}

function requireResolvedBrand(
  resolvedBrand: ResolvedBrandProfile | undefined,
): ResolvedBrandProfile {
  if (resolvedBrand === undefined) {
    throw new Error('Resolved Brand Profile is required before the Console can start.');
  }
  return resolvedBrand;
}

export function BrandApplicationLoading() {
  const brand = useBrandApplication();
  return (
    <ApplicationLoading
      applicationName={brand.applicationName}
      applicationLogoUrl={brand.resolvedBrand.profile.logoUrl}
      applicationLogoAlt={brand.logoAlt}
    />
  );
}

export function BrandConfigurationFailure({
  errorCode,
  onRetry,
}: {
  readonly errorCode: string;
  readonly onRetry: () => void;
}) {
  const brand = useBrandApplication();
  return (
    <ConfigurationFailure
      applicationName={brand.applicationName}
      applicationLogoUrl={brand.resolvedBrand.profile.logoUrl}
      applicationLogoAlt={brand.logoAlt}
      errorCode={errorCode}
      onRetry={onRetry}
    />
  );
}

export function useBrandApplication(): BrandApplicationContextValue {
  const value = useContext(BrandApplicationContext);
  if (value === undefined) {
    throw new Error('BrandApplicationProvider is required.');
  }
  return value;
}

export function brandApplicationTitle(
  resolvedBrand: ResolvedBrandProfile,
  surface: ConsoleBrandSurface,
): string {
  if (surface === 'platform') {
    return `${resolvedBrand.profile.displayName} Platform Console`;
  }
  return resolvedBrand.source === 'tenant'
    ? `${resolvedBrand.profile.displayName} · SaaS Forge Tenant Console`
    : `${resolvedBrand.profile.displayName} Tenant Console`;
}

function applyDocumentBrand(brand: BrandApplicationContextValue): () => void {
  const originalTitle = document.title;
  const existingIcon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
  const icon = existingIcon ?? document.createElement('link');
  const originalHref = icon.getAttribute('href');

  document.title = brand.documentTitle;
  icon.setAttribute('href', brand.resolvedBrand.profile.faviconUrl);
  if (existingIcon === null) {
    icon.rel = 'icon';
    document.head.append(icon);
  }

  return () => {
    document.title = originalTitle;
    if (existingIcon === null) {
      icon.remove();
    } else if (originalHref === null) {
      icon.removeAttribute('href');
    } else {
      icon.setAttribute('href', originalHref);
    }
  };
}
