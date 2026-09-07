import { describe, expect, it, vi } from 'vitest';

import {
  platformResolvedBrandProfile,
  resolveBrandProfile,
  type BrandAssetPreloader,
  type TenantBrandProfile,
} from '../src';

const completeProfile: TenantBrandProfile = {
  displayName: '  北辰科技  ',
  logoUrl: '/brands/beichen-logo.svg',
  faviconUrl: '/brands/beichen-favicon.ico',
  primaryColor: '#7c3aed',
  accentColor: '#2563eb',
};

const successfulPreloader: BrandAssetPreloader = ({ kind }) =>
  Promise.resolve({
    loaded: true,
    mimeType: kind === 'logo' ? 'image/svg+xml; charset=utf-8' : 'image/vnd.microsoft.icon',
  });

describe('Resolved Brand Profile', () => {
  it('从公共入口返回规范化且深度不可变的完整 Tenant 品牌', async () => {
    const result = await resolveBrandProfile(completeProfile, {
      preloadAsset: successfulPreloader,
    });

    expect(result.accepted).toBe(true);
    expect(result.resolvedBrand).toMatchObject({
      source: 'tenant',
      profile: {
        displayName: '北辰科技',
        logoUrl: '/brands/beichen-logo.svg',
        faviconUrl: '/brands/beichen-favicon.ico',
        primaryColor: '#7C3AED',
        accentColor: '#2563EB',
      },
    });
    expect(Object.isFrozen(result.resolvedBrand)).toBe(true);
    expect(Object.isFrozen(result.resolvedBrand.profile)).toBe(true);
    expect(Object.isFrozen(result.resolvedBrand.tokenSet)).toBe(true);
    expect(Object.isFrozen(result.resolvedBrand.tokenSet.light.primary)).toBe(true);
  });

  it('无 Tenant Profile 时直接返回完整平台品牌', async () => {
    await expect(resolveBrandProfile(undefined)).resolves.toEqual({
      accepted: true,
      resolvedBrand: platformResolvedBrandProfile,
    });
  });

  it('规范化显示名称的首尾空白与 Unicode 组合形式', async () => {
    const result = await resolveBrandProfile(
      { ...completeProfile, displayName: '  Cafe\u0301  ' },
      { preloadAsset: successfulPreloader },
    );

    expect(result.resolvedBrand.profile.displayName).toBe('Café');
  });

  it.each([
    [{ ...completeProfile, logoUrl: undefined }, 'PROFILE_INVALID'],
    [{ ...completeProfile, faviconUrl: '' }, 'ASSET_REFERENCE_INVALID'],
    [{ ...completeProfile, displayName: 'a'.repeat(201) }, 'PROFILE_INVALID'],
    [{ ...completeProfile, logoUrl: `/brands/${'a'.repeat(2041)}` }, 'PROFILE_INVALID'],
    [{ ...completeProfile, logoUrl: '/brands/logo\n.svg' }, 'PROFILE_INVALID'],
    [
      { ...completeProfile, logoUrl: 'https://assets.example.test/logo.svg' },
      'ASSET_REFERENCE_INVALID',
    ],
    [{ ...completeProfile, logoUrl: '//assets.example.test/logo.svg' }, 'ASSET_REFERENCE_INVALID'],
    [{ ...completeProfile, logoUrl: '/tenant-assets/logo.svg' }, 'ASSET_REFERENCE_INVALID'],
    [{ ...completeProfile, logoUrl: '/brands/%2e%2e/private.svg' }, 'ASSET_REFERENCE_INVALID'],
    [{ ...completeProfile, logoUrl: '/brands/logo.svg?tenant=secret' }, 'ASSET_REFERENCE_INVALID'],
    [{ ...completeProfile, primaryColor: '#GGGGGG' }, 'COLOR_INVALID'],
  ] as const)('拒绝不完整或不受控 Profile，并且不暴露原始值', async (profile, reason) => {
    const onRejected = vi.fn();
    const preloadAsset = vi.fn(successfulPreloader);

    const result = await resolveBrandProfile(profile, {
      onRejected,
      preloadAsset,
    });

    expect(result).toEqual({
      accepted: false,
      resolvedBrand: platformResolvedBrandProfile,
      reason,
    });
    expect(onRejected).toHaveBeenCalledWith(reason);
    expect(preloadAsset).not.toHaveBeenCalled();
    expect(JSON.stringify(result)).not.toContain('assets.example.test');
    expect(JSON.stringify(result)).not.toContain('#GGGGGG');
  });

  it('并行预加载两项素材且只在两项均成功后接受', async () => {
    const requests: string[] = [];
    const preloadAsset: BrandAssetPreloader = ({ kind, url }) => {
      requests.push(`${kind}:${url}`);
      return Promise.resolve({
        loaded: true,
        mimeType: kind === 'logo' ? 'image/webp' : 'image/png',
      });
    };

    const result = await resolveBrandProfile(completeProfile, { preloadAsset });

    expect(result.accepted).toBe(true);
    expect(requests).toEqual([
      'logo:/brands/beichen-logo.svg',
      'favicon:/brands/beichen-favicon.ico',
    ]);
  });

  it.each([
    [{ loaded: false, reason: 'http' }, 'ASSET_HTTP_ERROR'],
    [{ loaded: false, reason: 'decode' }, 'ASSET_DECODE_FAILED'],
    [{ loaded: false, reason: 'cancelled' }, 'ASSET_LOAD_CANCELLED'],
    [{ loaded: true, mimeType: 'text/html' }, 'ASSET_MIME_UNSUPPORTED'],
  ] as const)('将素材失败归一为稳定原因码 %s', async (loadResult, reason) => {
    const result = await resolveBrandProfile(completeProfile, {
      preloadAsset: () => Promise.resolve(loadResult),
    });

    expect(result).toEqual({
      accepted: false,
      resolvedBrand: platformResolvedBrandProfile,
      reason,
    });
  });

  it('外部取消后忽略不遵守 signal 的迟到加载结果', async () => {
    const controller = new AbortController();
    let finish: ((value: Awaited<ReturnType<BrandAssetPreloader>>) => void) | undefined;
    const preloadAsset: BrandAssetPreloader = () =>
      new Promise((resolve) => {
        finish = resolve;
      });

    const resolution = resolveBrandProfile(completeProfile, {
      signal: controller.signal,
      preloadAsset,
    });
    controller.abort();
    finish?.({ loaded: true, mimeType: 'image/svg+xml' });

    await expect(resolution).resolves.toEqual({
      accepted: false,
      resolvedBrand: platformResolvedBrandProfile,
      reason: 'ASSET_LOAD_CANCELLED',
    });
  });

  it('素材完成后发现请求已过期时拒绝迟到结果', async () => {
    const result = await resolveBrandProfile(completeProfile, {
      preloadAsset: successfulPreloader,
      isCurrent: () => false,
    });

    expect(result).toEqual({
      accepted: false,
      resolvedBrand: platformResolvedBrandProfile,
      reason: 'ASSET_LOAD_STALE',
    });
  });

  it('诊断回调异常不能阻止完整平台回退', async () => {
    const result = await resolveBrandProfile(
      { ...completeProfile, logoUrl: '/outside/logo.svg' },
      {
        onRejected: () => {
          throw new Error('diagnostic failed');
        },
      },
    );

    expect(result.accepted).toBe(false);
    expect(result.resolvedBrand).toBe(platformResolvedBrandProfile);
  });
});
