import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  contrastRatio,
  DesignSystemProvider,
  resolveTenantBrandProfile,
  semanticTokens,
  type TenantBrandProfile,
} from '../src';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const tenantBrand: TenantBrandProfile = {
  displayName: '北辰科技',
  logoUrl: '/tenant-assets/beichen-logo.svg',
  faviconUrl: 'https://assets.example.test/beichen.ico',
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
    expect(root.getAttribute('lang')).toBe('zh-CN');
  });

  it('为两种主题原子生成可读品牌颜色与前景色', () => {
    const resolution = resolveTenantBrandProfile(tenantBrand);
    expect(resolution.accepted).toBe(true);
    if (!resolution.accepted) {
      return;
    }

    for (const [scheme, surface] of [
      [resolution.light, semanticTokens.color.light.surface],
      [resolution.dark, semanticTokens.color.dark.surface],
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
    render(
      <DesignSystemProvider
        forcedColorScheme="light"
        tenantBrand={{ ...tenantBrand, faviconUrl: 'javascript:alert(1)', accentColor: '#GGGGGG' }}
        onTenantBrandRejected={rejected}
      >
        <p>平台回退</p>
      </DesignSystemProvider>,
    );

    const root = providerRoot('平台回退');
    expect(root.dataset.brand).toBe('platform');
    expect(root.style.getPropertyValue('--sf-color-primary')).toBe(
      semanticTokens.color.platformPrimary,
    );
    expect(root.style.getPropertyValue('--sf-color-accent')).toBe(
      semanticTokens.color.platformPrimary,
    );
    await waitFor(() => {
      expect(rejected).toHaveBeenCalledOnce();
    });
  });

  it('拒绝会与固定危险状态混淆的品牌颜色', () => {
    const resolution = resolveTenantBrandProfile({
      ...tenantBrand,
      primaryColor: semanticTokens.color.status.danger,
    });

    expect(resolution).toEqual({
      accepted: false,
      reason: 'Tenant 品牌颜色与固定状态色无法可靠区分。',
    });
  });

  it('允许展示册固定浅色、深色和英文，但不改变默认系统跟随行为', () => {
    const { rerender } = render(
      <DesignSystemProvider forcedColorScheme="light" locale="en-US" tenantBrand={tenantBrand}>
        <p>Preview</p>
      </DesignSystemProvider>,
    );
    let root = providerRoot('Preview');
    expect(root.dataset.colorScheme).toBe('light');
    expect(root.dataset.brand).toBe('tenant');
    expect(root.getAttribute('lang')).toBe('en-US');

    rerender(
      <DesignSystemProvider forcedColorScheme="dark" locale="en-US" tenantBrand={tenantBrand}>
        <p>Preview</p>
      </DesignSystemProvider>,
    );
    root = providerRoot('Preview');
    expect(root.dataset.colorScheme).toBe('dark');
  });
});
