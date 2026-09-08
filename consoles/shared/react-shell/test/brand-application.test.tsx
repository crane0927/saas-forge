import { platformResolvedBrandProfile } from '@saas-forge/design-system';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { BrandApplicationLoading, BrandApplicationProvider } from '../src';

const updatedPlatformBrand = {
  source: 'platform',
  profile: {
    displayName: 'Updated Forge',
    logoUrl: '/updated-logo.svg',
    faviconUrl: '/updated-favicon.svg',
    primaryColor: '#123456',
    accentColor: '#654321',
  },
  tokenSet: {
    light: {
      primary: { color: '#123456', foreground: '#FFFFFF' },
      accent: { color: '#654321', foreground: '#FFFFFF' },
    },
    dark: {
      primary: { color: '#234567', foreground: '#FFFFFF' },
      accent: { color: '#765432', foreground: '#FFFFFF' },
    },
  },
} as const;

afterEach(cleanup);

describe('BrandApplicationProvider', () => {
  it('applies one Platform profile to the provider, identity, favicon, and document title', () => {
    document.title = 'Original title';
    const originalIcon = document.createElement('link');
    originalIcon.rel = 'icon';
    originalIcon.href = '/original.svg';
    document.head.append(originalIcon);

    const view = render(
      <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
        <BrandApplicationLoading />
      </BrandApplicationProvider>,
    );

    expect(document.title).toBe('SaaS Forge Platform Console');
    expect(originalIcon.getAttribute('href')).toBe(platformResolvedBrandProfile.profile.faviconUrl);
    expect(screen.getByRole('img', { name: 'SaaS Forge Logo' }).getAttribute('src')).toBe(
      platformResolvedBrandProfile.profile.logoUrl,
    );
    expect(document.querySelector('.sf-design-system-root')?.getAttribute('data-brand')).toBe(
      'platform',
    );

    view.unmount();
    expect(document.title).toBe('Original title');
    expect(originalIcon.getAttribute('href')).toBe('/original.svg');
    originalIcon.remove();
  });

  it.each(['platform', 'tenant'] as const)(
    'applies the %s Console title through the public provider',
    (surface) => {
      render(
        <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface={surface}>
          <BrandApplicationLoading />
        </BrandApplicationProvider>,
      );
      expect(document.title).toBe(
        surface === 'platform' ? 'SaaS Forge Platform Console' : 'SaaS Forge Tenant Console',
      );
    },
  );

  it('commits every branded surface from the replacement profile together', () => {
    const view = render(
      <BrandApplicationProvider resolvedBrand={platformResolvedBrandProfile} surface="platform">
        <BrandApplicationLoading />
      </BrandApplicationProvider>,
    );

    view.rerender(
      <BrandApplicationProvider resolvedBrand={updatedPlatformBrand} surface="platform">
        <BrandApplicationLoading />
      </BrandApplicationProvider>,
    );

    expect(screen.queryByText('SaaS Forge')).toBeNull();
    expect(screen.getByText('Updated Forge')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Updated Forge Logo' }).getAttribute('src')).toBe(
      updatedPlatformBrand.profile.logoUrl,
    );
    expect(document.title).toBe('Updated Forge Platform Console');
    expect(document.querySelector('link[rel~="icon"]')?.getAttribute('href')).toBe(
      updatedPlatformBrand.profile.faviconUrl,
    );
    const provider = document.querySelector<HTMLElement>('.sf-design-system-root');
    expect(provider?.style.getPropertyValue('--sf-color-primary')).toBe(
      updatedPlatformBrand.tokenSet.light.primary.color,
    );
    expect(provider?.style.getPropertyValue('--sf-color-accent')).toBe(
      updatedPlatformBrand.tokenSet.light.accent.color,
    );
  });

  it('fails closed when the resolved profile is unavailable', () => {
    expect(() =>
      render(
        <BrandApplicationProvider resolvedBrand={undefined} surface="platform">
          <p>must not render</p>
        </BrandApplicationProvider>,
      ),
    ).toThrow('Resolved Brand Profile is required');
  });
});
