import { semanticTokens } from './tokens';

export interface TenantBrandProfile {
  readonly displayName: string;
  readonly logoUrl?: string;
  readonly faviconUrl?: string;
  readonly primaryColor: string;
  readonly accentColor: string;
}

export interface BrandColorPair {
  readonly color: string;
  readonly foreground: '#FFFFFF' | '#0F172A';
}

export interface ResolvedBrandTheme {
  readonly primary: BrandColorPair;
  readonly accent: BrandColorPair;
}

export type TenantBrandResolution =
  | {
      readonly accepted: true;
      readonly profile: TenantBrandProfile;
      readonly light: ResolvedBrandTheme;
      readonly dark: ResolvedBrandTheme;
    }
  | {
      readonly accepted: false;
      readonly reason: string;
    };

interface RgbColor {
  readonly red: number;
  readonly green: number;
  readonly blue: number;
}

const HEX_COLOR = /^#[0-9A-Fa-f]{6}$/;
const MINIMUM_TEXT_CONTRAST = 4.5;
const MINIMUM_UI_CONTRAST = 3;
const MINIMUM_STATUS_HUE_DISTANCE = 24;

export function contrastRatio(first: string, second: string): number {
  const firstLuminance = relativeLuminance(parseHexColor(first));
  const secondLuminance = relativeLuminance(parseHexColor(second));
  const lighter = Math.max(firstLuminance, secondLuminance);
  const darker = Math.min(firstLuminance, secondLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * 同时解析浅色与深色品牌 Token；任一颜色、前景或素材引用不合法时拒绝整份 Profile。
 */
export function resolveTenantBrandProfile(profile: TenantBrandProfile): TenantBrandResolution {
  if (profile.displayName.trim() === '' || containsControlCharacter(profile.displayName)) {
    return rejected('Tenant 品牌显示名称不合法。');
  }
  if (!isControlledAssetUrl(profile.logoUrl) || !isControlledAssetUrl(profile.faviconUrl)) {
    return rejected('Tenant 品牌素材必须使用受控 HTTPS 或站内引用。');
  }
  if (!HEX_COLOR.test(profile.primaryColor) || !HEX_COLOR.test(profile.accentColor)) {
    return rejected('Tenant 品牌颜色必须使用完整十六进制格式。');
  }

  const primary = profile.primaryColor.toUpperCase();
  const accent = profile.accentColor.toUpperCase();
  if (!isDistinctFromStatusColors(primary) || !isDistinctFromStatusColors(accent)) {
    return rejected('Tenant 品牌颜色与固定状态色无法可靠区分。');
  }
  const light = resolveTheme(primary, accent, semanticTokens.color.light.surface, '#000000');
  const dark = resolveTheme(primary, accent, semanticTokens.color.dark.surface, '#FFFFFF');
  if (light === undefined || dark === undefined) {
    return rejected('Tenant 品牌颜色无法同时满足浅色与深色对比度要求。');
  }

  return {
    accepted: true,
    profile: { ...profile, primaryColor: primary, accentColor: accent },
    light,
    dark,
  };
}

function resolveTheme(
  primary: string,
  accent: string,
  surface: string,
  adjustmentTarget: '#000000' | '#FFFFFF',
): ResolvedBrandTheme | undefined {
  const resolvedPrimary = resolveColorPair(primary, surface, adjustmentTarget);
  const resolvedAccent = resolveColorPair(accent, surface, adjustmentTarget);
  if (resolvedPrimary === undefined || resolvedAccent === undefined) {
    return undefined;
  }
  return { primary: resolvedPrimary, accent: resolvedAccent };
}

function resolveColorPair(
  requestedColor: string,
  surface: string,
  adjustmentTarget: '#000000' | '#FFFFFF',
): BrandColorPair | undefined {
  let color = requestedColor;
  for (let step = 0; step <= 20; step += 1) {
    if (contrastRatio(color, surface) >= MINIMUM_UI_CONTRAST) {
      const whiteContrast = contrastRatio(color, '#FFFFFF');
      const darkContrast = contrastRatio(color, '#0F172A');
      const foreground = whiteContrast >= darkContrast ? '#FFFFFF' : '#0F172A';
      if (Math.max(whiteContrast, darkContrast) >= MINIMUM_TEXT_CONTRAST) {
        return { color, foreground };
      }
    }
    color = mixColors(requestedColor, adjustmentTarget, (step + 1) / 20);
  }
  return undefined;
}

function rejected(reason: string): TenantBrandResolution {
  return { accepted: false, reason };
}

function isControlledAssetUrl(value: string | undefined): boolean {
  if (value === undefined) {
    return true;
  }
  if (value.startsWith('/') && !value.startsWith('//')) {
    return true;
  }
  try {
    return new URL(value).protocol === 'https:';
  } catch {
    return false;
  }
}

function containsControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 32 || codePoint === 127;
  });
}

function isDistinctFromStatusColors(color: string): boolean {
  const candidateHue = hue(parseHexColor(color));
  if (candidateHue === undefined) {
    return true;
  }
  return Object.values(semanticTokens.color.status).every((statusColor) => {
    const statusHue = hue(parseHexColor(statusColor));
    if (statusHue === undefined) {
      return true;
    }
    const distance = Math.abs(candidateHue - statusHue);
    return Math.min(distance, 360 - distance) >= MINIMUM_STATUS_HUE_DISTANCE;
  });
}

function hue(color: RgbColor): number | undefined {
  const red = color.red / 255;
  const green = color.green / 255;
  const blue = color.blue / 255;
  const maximum = Math.max(red, green, blue);
  const minimum = Math.min(red, green, blue);
  const difference = maximum - minimum;
  if (difference < 0.1) {
    return undefined;
  }
  if (maximum === red) {
    return (60 * ((green - blue) / difference) + 360) % 360;
  }
  if (maximum === green) {
    return 60 * ((blue - red) / difference + 2);
  }
  return 60 * ((red - green) / difference + 4);
}

function parseHexColor(value: string): RgbColor {
  if (!HEX_COLOR.test(value)) {
    throw new Error(`无法计算非法颜色 ${value} 的对比度。`);
  }
  return {
    red: Number.parseInt(value.slice(1, 3), 16),
    green: Number.parseInt(value.slice(3, 5), 16),
    blue: Number.parseInt(value.slice(5, 7), 16),
  };
}

function mixColors(first: string, second: string, amount: number): string {
  const from = parseHexColor(first);
  const to = parseHexColor(second);
  const channels = [from.red, from.green, from.blue];
  const targets = [to.red, to.green, to.blue];
  return `#${channels
    .map((channel, index) =>
      Math.round(channel + ((targets[index] ?? channel) - channel) * amount)
        .toString(16)
        .padStart(2, '0'),
    )
    .join('')}`.toUpperCase();
}

function relativeLuminance(color: RgbColor): number {
  return (
    0.2126 * linearChannel(color.red) +
    0.7152 * linearChannel(color.green) +
    0.0722 * linearChannel(color.blue)
  );
}

function linearChannel(channel: number): number {
  const normalized = channel / 255;
  return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
}
