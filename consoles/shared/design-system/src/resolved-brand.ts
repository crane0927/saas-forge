import {
  resolveTenantBrandProfile,
  type ResolvedBrandTheme,
  type TenantBrandProfile,
} from './brand-theme';
import { platformBrandProfile, platformBrandTokenSet } from './platform-brand';

export type BrandSource = 'platform' | 'tenant';
export type BrandAssetKind = 'logo' | 'favicon';

export interface CompleteBrandProfile {
  readonly displayName: string;
  readonly logoUrl: string;
  readonly faviconUrl: string;
  readonly primaryColor: string;
  readonly accentColor: string;
}

export interface BrandTokenSet {
  readonly light: ResolvedBrandTheme;
  readonly dark: ResolvedBrandTheme;
}

export interface ResolvedBrandProfile {
  readonly source: BrandSource;
  readonly profile: CompleteBrandProfile;
  readonly tokenSet: BrandTokenSet;
}

export type BrandRejectionReasonCode =
  | 'PROFILE_INVALID'
  | 'ASSET_REFERENCE_INVALID'
  | 'COLOR_INVALID'
  | 'ASSET_HTTP_ERROR'
  | 'ASSET_MIME_UNSUPPORTED'
  | 'ASSET_DECODE_FAILED'
  | 'ASSET_LOAD_CANCELLED'
  | 'ASSET_LOAD_STALE';

export type BrandResolution =
  | {
      readonly accepted: true;
      readonly resolvedBrand: ResolvedBrandProfile;
    }
  | {
      readonly accepted: false;
      readonly resolvedBrand: ResolvedBrandProfile;
      readonly reason: BrandRejectionReasonCode;
    };

export type BrandAssetLoadResult =
  | {
      readonly loaded: true;
      readonly mimeType: string;
    }
  | {
      readonly loaded: false;
      readonly reason: 'http' | 'decode' | 'cancelled';
    };

export interface BrandAssetLoadRequest {
  readonly kind: BrandAssetKind;
  readonly url: string;
  readonly signal: AbortSignal;
}

export type BrandAssetPreloader = (request: BrandAssetLoadRequest) => Promise<BrandAssetLoadResult>;

/** 测试或隔离预览可替换素材加载器，但不能绕过路径、MIME 或完整性校验。 */
export interface ResolveBrandProfileOptions {
  readonly signal?: AbortSignal;
  readonly isCurrent?: () => boolean;
  readonly preloadAsset?: BrandAssetPreloader;
  readonly onRejected?: (reason: BrandRejectionReasonCode) => void;
}

const CONTROLLED_ASSET_PATH_PREFIX = '/brands/';
const ENCODED_PATH_SEPARATOR_OR_DOT = /%(?:2e|2f|5c)/i;
const LOGO_MIME_TYPES = new Set(['image/png', 'image/svg+xml', 'image/webp']);
const FAVICON_MIME_TYPES = new Set([
  'image/png',
  'image/svg+xml',
  'image/x-icon',
  'image/vnd.microsoft.icon',
]);

export const platformResolvedBrandProfile: ResolvedBrandProfile = freezeResolvedBrand({
  source: 'platform',
  profile: platformBrandProfile,
  tokenSet: platformBrandTokenSet,
});

/**
 * 解析完整 Tenant 品牌；失败时返回同一个不可变 Platform 品牌，调用方不得拼接原始 Profile。
 */
export async function resolveBrandProfile(
  tenantProfile: TenantBrandProfile | undefined,
  options: ResolveBrandProfileOptions = {},
): Promise<BrandResolution> {
  if (tenantProfile === undefined) {
    return { accepted: true, resolvedBrand: platformResolvedBrandProfile };
  }

  const completeProfile = normalizeCompleteProfile(tenantProfile);
  if (completeProfile === undefined) {
    return rejected('PROFILE_INVALID', options.onRejected);
  }
  if (
    !isControlledAssetReference(completeProfile.logoUrl) ||
    !isControlledAssetReference(completeProfile.faviconUrl)
  ) {
    return rejected('ASSET_REFERENCE_INVALID', options.onRejected);
  }

  const colorResolution = resolveTenantBrandProfile(completeProfile);
  if (!colorResolution.accepted) {
    return rejected('COLOR_INVALID', options.onRejected);
  }

  const preloadAsset = options.preloadAsset ?? preloadBrowserAsset;
  const preloadResult = await preloadBrandAssets(completeProfile, preloadAsset, options.signal);
  if (preloadResult !== undefined) {
    return rejected(preloadResult, options.onRejected);
  }
  if (options.isCurrent?.() === false) {
    return rejected('ASSET_LOAD_STALE', options.onRejected);
  }

  return {
    accepted: true,
    resolvedBrand: freezeResolvedBrand({
      source: 'tenant',
      profile: {
        ...completeProfile,
        primaryColor: colorResolution.profile.primaryColor,
        accentColor: colorResolution.profile.accentColor,
      },
      tokenSet: { light: colorResolution.light, dark: colorResolution.dark },
    }),
  };
}

function normalizeCompleteProfile(profile: TenantBrandProfile): CompleteBrandProfile | undefined {
  if (
    typeof profile.displayName !== 'string' ||
    typeof profile.logoUrl !== 'string' ||
    typeof profile.faviconUrl !== 'string' ||
    typeof profile.primaryColor !== 'string' ||
    typeof profile.accentColor !== 'string'
  ) {
    return undefined;
  }
  const displayName = profile.displayName.normalize('NFC').trim();
  if (
    displayName === '' ||
    displayName.length > 200 ||
    containsControlCharacter(displayName) ||
    profile.logoUrl.length > 2048 ||
    profile.faviconUrl.length > 2048 ||
    containsControlCharacter(profile.logoUrl) ||
    containsControlCharacter(profile.faviconUrl) ||
    profile.logoUrl.trim() !== profile.logoUrl ||
    profile.faviconUrl.trim() !== profile.faviconUrl
  ) {
    return undefined;
  }
  return {
    displayName,
    logoUrl: profile.logoUrl,
    faviconUrl: profile.faviconUrl,
    primaryColor: profile.primaryColor,
    accentColor: profile.accentColor,
  };
}

function isControlledAssetReference(value: string): boolean {
  if (
    !value.startsWith(CONTROLLED_ASSET_PATH_PREFIX) ||
    value.startsWith('//') ||
    ENCODED_PATH_SEPARATOR_OR_DOT.test(value)
  ) {
    return false;
  }
  try {
    const parsed = new URL(value, 'https://brand.invalid');
    return (
      parsed.origin === 'https://brand.invalid' &&
      parsed.pathname.startsWith(CONTROLLED_ASSET_PATH_PREFIX) &&
      parsed.search === '' &&
      parsed.hash === '' &&
      parsed.pathname.length > CONTROLLED_ASSET_PATH_PREFIX.length
    );
  } catch {
    return false;
  }
}

async function preloadBrandAssets(
  profile: CompleteBrandProfile,
  preloadAsset: BrandAssetPreloader,
  outerSignal: AbortSignal | undefined,
): Promise<BrandRejectionReasonCode | undefined> {
  if (outerSignal?.aborted === true) {
    return 'ASSET_LOAD_CANCELLED';
  }

  const controller = new AbortController();
  const abort = () => {
    controller.abort();
  };
  outerSignal?.addEventListener('abort', abort, { once: true });
  try {
    const results = await Promise.all([
      raceAssetPreload(
        preloadAsset({ kind: 'logo', url: profile.logoUrl, signal: controller.signal }),
        controller.signal,
      ),
      raceAssetPreload(
        preloadAsset({ kind: 'favicon', url: profile.faviconUrl, signal: controller.signal }),
        controller.signal,
      ),
    ]);
    if (controller.signal.aborted) {
      return 'ASSET_LOAD_CANCELLED';
    }
    for (const [index, result] of results.entries()) {
      if (!result.loaded) {
        return assetLoadReason(result.reason);
      }
      const kind: BrandAssetKind = index === 0 ? 'logo' : 'favicon';
      if (!isAllowedMimeType(kind, result.mimeType)) {
        return 'ASSET_MIME_UNSUPPORTED';
      }
    }
    return undefined;
  } catch {
    return controller.signal.aborted ? 'ASSET_LOAD_CANCELLED' : 'ASSET_HTTP_ERROR';
  } finally {
    outerSignal?.removeEventListener('abort', abort);
  }
}

function raceAssetPreload(
  preload: Promise<BrandAssetLoadResult>,
  signal: AbortSignal,
): Promise<BrandAssetLoadResult> {
  if (signal.aborted) {
    return Promise.resolve({ loaded: false, reason: 'cancelled' });
  }
  return new Promise((resolve) => {
    const cancel = () => {
      resolve({ loaded: false, reason: 'cancelled' });
    };
    signal.addEventListener('abort', cancel, { once: true });
    void preload.then(
      (result) => {
        signal.removeEventListener('abort', cancel);
        resolve(result);
      },
      () => {
        signal.removeEventListener('abort', cancel);
        resolve({ loaded: false, reason: 'http' });
      },
    );
  });
}

async function preloadBrowserAsset({
  kind,
  url,
  signal,
}: BrandAssetLoadRequest): Promise<BrandAssetLoadResult> {
  let response: Response;
  try {
    response = await fetch(url, {
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
      signal,
    });
  } catch {
    return { loaded: false, reason: signal.aborted ? 'cancelled' : 'http' };
  }
  if (!response.ok || response.redirected) {
    return { loaded: false, reason: 'http' };
  }

  const mimeType = normalizedMimeType(response.headers.get('content-type') ?? '');
  if (!isAllowedMimeType(kind, mimeType)) {
    return { loaded: true, mimeType };
  }
  let blob: Blob;
  try {
    blob = await response.blob();
  } catch {
    return { loaded: false, reason: signal.aborted ? 'cancelled' : 'http' };
  }
  if (signal.aborted) {
    return { loaded: false, reason: 'cancelled' };
  }
  if (!(await decodeImage(blob, signal))) {
    return { loaded: false, reason: isSignalAborted(signal) ? 'cancelled' : 'decode' };
  }
  return { loaded: true, mimeType };
}

async function decodeImage(blob: Blob, signal: AbortSignal): Promise<boolean> {
  if (typeof Image === 'undefined' || typeof URL.createObjectURL !== 'function') {
    return false;
  }
  const objectUrl = URL.createObjectURL(blob);
  const image = new Image();
  try {
    image.src = objectUrl;
    await raceImageDecode(image, signal);
    return image.naturalWidth > 0 && image.naturalHeight > 0;
  } catch {
    return false;
  } finally {
    image.src = '';
    URL.revokeObjectURL(objectUrl);
  }
}

function raceImageDecode(image: HTMLImageElement, signal: AbortSignal): Promise<void> {
  if (signal.aborted) {
    return Promise.reject(new DOMException('Brand asset load cancelled.', 'AbortError'));
  }
  return new Promise((resolve, reject) => {
    const abort = () => {
      reject(new DOMException('Brand asset load cancelled.', 'AbortError'));
    };
    signal.addEventListener('abort', abort, { once: true });
    void image.decode().then(
      () => {
        signal.removeEventListener('abort', abort);
        resolve();
      },
      (error: unknown) => {
        signal.removeEventListener('abort', abort);
        reject(error instanceof Error ? error : new Error('Brand asset decode failed.'));
      },
    );
  });
}

function isAllowedMimeType(kind: BrandAssetKind, value: string): boolean {
  const mimeType = normalizedMimeType(value);
  return (kind === 'logo' ? LOGO_MIME_TYPES : FAVICON_MIME_TYPES).has(mimeType);
}

function normalizedMimeType(value: string): string {
  return value.split(';', 1)[0]?.trim().toLowerCase() ?? '';
}

function isSignalAborted(signal: AbortSignal): boolean {
  return signal.aborted;
}

function assetLoadReason(
  reason: Exclude<BrandAssetLoadResult, { readonly loaded: true }>['reason'],
): BrandRejectionReasonCode {
  if (reason === 'decode') return 'ASSET_DECODE_FAILED';
  if (reason === 'cancelled') return 'ASSET_LOAD_CANCELLED';
  return 'ASSET_HTTP_ERROR';
}

function rejected(
  reason: BrandRejectionReasonCode,
  onRejected: ResolveBrandProfileOptions['onRejected'],
): BrandResolution {
  try {
    onRejected?.(reason);
  } catch {
    // 诊断回调不能改变安全回退结果，也不能通过日志泄露原始 Profile。
  }
  return { accepted: false, resolvedBrand: platformResolvedBrandProfile, reason };
}

function freezeResolvedBrand(value: {
  readonly source: BrandSource;
  readonly profile: CompleteBrandProfile;
  readonly tokenSet: BrandTokenSet;
}): ResolvedBrandProfile {
  const profile = Object.freeze({ ...value.profile });
  const light = freezeTheme(value.tokenSet.light);
  const dark = freezeTheme(value.tokenSet.dark);
  return Object.freeze({
    source: value.source,
    profile,
    tokenSet: Object.freeze({ light, dark }),
  });
}

function freezeTheme(theme: ResolvedBrandTheme): ResolvedBrandTheme {
  return Object.freeze({
    primary: Object.freeze({ ...theme.primary }),
    accent: Object.freeze({ ...theme.accent }),
  });
}

function containsControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 32 || codePoint === 127;
  });
}
