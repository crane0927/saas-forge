import type { TenantBrandProfile } from '../src/brand-theme';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  contrastRatio,
  DesignSystemProvider,
  platformBrandProfile,
  platformResolvedBrandProfile,
  platformBrandTokenSet,
  platformConsoleTitle,
  resolveBrandProfile,
  semanticTokens,
  tenantConsolePlatformTitle,
} from '../src';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const tenantBrand: TenantBrandProfile = {
  displayName: '北辰科技',
  logoUrl: '/brands/beichen-logo.svg',
  faviconUrl: '/brands/beichen.ico',
  primaryColor: '#7C3AED',
  accentColor: '#C026D3',
};

function providerRoot(content: string): HTMLElement {
  const root = screen.getByText(content).parentElement;
  if (root === null) {
    throw new Error('测试内容缺少 Design System Provider 根元素。');
  }
  return root;
}

describe('Design System 主题与品牌', () => {
  it('发布完整且不可变的 Platform Brand Profile 与精确 Console 标题', () => {
    expect(platformBrandProfile.displayName).toBe('SaaS Forge');
    expect(platformBrandProfile.logoUrl).toContain('platform-logo.svg');
    expect(platformBrandProfile.faviconUrl).toContain('platform-favicon.svg');
    expect(platformBrandProfile.primaryColor).toBe('#2563EB');
    expect(platformBrandProfile.accentColor).toBe('#C026D3');
    expect(Object.isFrozen(platformBrandProfile)).toBe(true);
    expect(platformConsoleTitle).toBe('SaaS Forge Platform Console');
    expect(tenantConsolePlatformTitle).toBe('SaaS Forge Tenant Console');
  });

  it('Platform Brand Token Set 在浅色和深色主题保持可读', () => {
    for (const [scheme, surface] of [
      [platformBrandTokenSet.light, semanticTokens.color.light.surface],
      [platformBrandTokenSet.dark, semanticTokens.color.dark.surface],
    ] as const) {
      expect(contrastRatio(scheme.primary.color, surface)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(scheme.primary.color, scheme.primary.foreground)).toBeGreaterThanOrEqual(
        4.5,
      );
      expect(contrastRatio(scheme.accent.color, surface)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(scheme.accent.color, scheme.accent.foreground)).toBeGreaterThanOrEqual(
        4.5,
      );
    }
  });

  it('默认跟随操作系统主题并使用唯一平台主色 Token', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query) =>
        ({
          matches: query === '(prefers-color-scheme: dark)',
          media: query,
          onchange: null,
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
          addListener: () => undefined,
          removeListener: () => undefined,
          dispatchEvent: () => false,
        }) as MediaQueryList,
    );

    render(
      <DesignSystemProvider>
        <p>主题内容</p>
      </DesignSystemProvider>,
    );

    const root = providerRoot('主题内容');
    expect(root.dataset.colorScheme).toBe('dark');
    expect(root.style.getPropertyValue('--sf-color-primary')).toBe(
      semanticTokens.color.platformPrimary,
    );
    expect(root.style.getPropertyValue('--sf-color-accent')).toBe(
      semanticTokens.color.platformAccent,
    );
    expect(root.getAttribute('lang')).toBe('zh-CN');
  });

  it.each(['light', 'dark'] as const)('直接消费已解析品牌的 %s Token', (scheme) => {
    render(
      <DesignSystemProvider forcedColorScheme={scheme} resolvedBrand={platformResolvedBrandProfile}>
        <p>{scheme}</p>
      </DesignSystemProvider>,
    );

    const root = providerRoot(scheme);
    expect(root.dataset.brand).toBe('platform');
    expect(root.style.getPropertyValue('--sf-color-primary')).toBe(
      platformResolvedBrandProfile.tokenSet[scheme].primary.color,
    );
    expect(root.style.getPropertyValue('--sf-color-accent')).toBe(
      platformResolvedBrandProfile.tokenSet[scheme].accent.color,
    );
  });

  it('为两种主题原子生成可读品牌颜色与前景色', async () => {
    const resolution = await resolveBrandProfile(tenantBrand, {
      preloadAsset: () => Promise.resolve({ loaded: true, mimeType: 'image/svg+xml' }),
    });
    expect(resolution.accepted).toBe(true);
    if (!resolution.accepted) {
      return;
    }

    for (const [scheme, surface] of [
      [resolution.resolvedBrand.tokenSet.light, semanticTokens.color.light.surface],
      [resolution.resolvedBrand.tokenSet.dark, semanticTokens.color.dark.surface],
    ] as const) {
      expect(contrastRatio(scheme.primary.color, surface)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(scheme.primary.color, scheme.primary.foreground)).toBeGreaterThanOrEqual(
        4.5,
      );
      expect(contrastRatio(scheme.accent.color, surface)).toBeGreaterThanOrEqual(3);
      expect(contrastRatio(scheme.accent.color, scheme.accent.foreground)).toBeGreaterThanOrEqual(
        4.5,
      );
    }
  });

  it('非法品牌不发生部分切换并报告拒绝原因', async () => {
    const rejected = vi.fn();
    const resolution = await resolveBrandProfile(
      { ...tenantBrand, faviconUrl: 'javascript:alert(1)', accentColor: '#GGGGGG' },
      { onRejected: rejected },
    );
    render(
      <DesignSystemProvider forcedColorScheme="light" resolvedBrand={resolution.resolvedBrand}>
        <p>平台回退</p>
      </DesignSystemProvider>,
    );

    const root = providerRoot('平台回退');
    expect(root.dataset.brand).toBe('platform');
    expect(root.style.getPropertyValue('--sf-color-primary')).toBe(
      semanticTokens.color.platformPrimary,
    );
    expect(root.style.getPropertyValue('--sf-color-accent')).toBe(
      semanticTokens.color.platformAccent,
    );
    expect(rejected).toHaveBeenCalledExactlyOnceWith('ASSET_REFERENCE_INVALID');
  });

  it('拒绝会与固定危险状态混淆的品牌颜色', async () => {
    const resolution = await resolveBrandProfile({
      ...tenantBrand,
      primaryColor: semanticTokens.color.status.danger,
    });

    expect(resolution).toEqual({
      accepted: false,
      reason: 'COLOR_INVALID',
      resolvedBrand: platformResolvedBrandProfile,
    });
  });

  it('允许展示册固定浅色、深色和英文，但不改变默认系统跟随行为', async () => {
    const resolution = await resolveBrandProfile(tenantBrand, {
      preloadAsset: () => Promise.resolve({ loaded: true, mimeType: 'image/svg+xml' }),
    });
    const { rerender } = render(
      <DesignSystemProvider
        forcedColorScheme="light"
        locale="en-US"
        resolvedBrand={resolution.resolvedBrand}
      >
        <p>Preview</p>
      </DesignSystemProvider>,
    );
    let root = providerRoot('Preview');
    expect(root.dataset.colorScheme).toBe('light');
    expect(root.dataset.brand).toBe('tenant');
    expect(root.getAttribute('lang')).toBe('en-US');

    rerender(
      <DesignSystemProvider
        forcedColorScheme="dark"
        locale="en-US"
        resolvedBrand={resolution.resolvedBrand}
      >
        <p>Preview</p>
      </DesignSystemProvider>,
    );
    root = providerRoot('Preview');
    expect(root.dataset.colorScheme).toBe('dark');
  });
});
