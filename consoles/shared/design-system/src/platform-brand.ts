import platformFaviconUrl from './assets/platform-favicon.svg?no-inline';
import platformLogoUrl from './assets/platform-logo.svg?no-inline';
import type { ResolvedBrandTheme } from './brand-theme';
import { semanticTokens } from './tokens';

export interface PlatformBrandProfile {
  readonly displayName: string;
  readonly logoUrl: string;
  readonly faviconUrl: string;
  readonly primaryColor: string;
  readonly accentColor: string;
}

export interface PlatformBrandTokenSet {
  readonly light: ResolvedBrandTheme;
  readonly dark: ResolvedBrandTheme;
}

export const platformConsoleTitle = 'SaaS Forge Platform Console';
export const tenantConsolePlatformTitle = 'SaaS Forge Tenant Console';

export const platformBrandProfile: PlatformBrandProfile = Object.freeze({
  displayName: 'SaaS Forge',
  logoUrl: platformLogoUrl,
  faviconUrl: platformFaviconUrl,
  primaryColor: semanticTokens.color.platformPrimary,
  accentColor: semanticTokens.color.platformAccent,
});

const platformPrimary = Object.freeze({
  color: semanticTokens.color.platformPrimary,
  foreground: '#FFFFFF' as const,
});
const platformAccent = Object.freeze({
  color: semanticTokens.color.platformAccent,
  foreground: '#FFFFFF' as const,
});

export const platformBrandTokenSet: PlatformBrandTokenSet = Object.freeze({
  light: Object.freeze({ primary: platformPrimary, accent: platformAccent }),
  dark: Object.freeze({ primary: platformPrimary, accent: platformAccent }),
});
