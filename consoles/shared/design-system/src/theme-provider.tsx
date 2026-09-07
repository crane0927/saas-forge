import { ConfigProvider, theme as antTheme, type ThemeConfig } from 'antd';
import type { Locale } from 'antd/es/locale';
import enUS from 'antd/locale/en_US';
import zhCN from 'antd/locale/zh_CN';
import type { SupportedLocale } from '@saas-forge/i18n';
import { createContext, useContext } from 'react';
import {
  useEffect,
  useMemo,
  useSyncExternalStore,
  type CSSProperties,
  type ReactNode,
} from 'react';

import { resolveTenantBrandProfile, type TenantBrandProfile } from './brand-theme';
import { platformBrandTokenSet } from './platform-brand';
import { semanticTokens } from './tokens';

export type DesignSystemLocale = SupportedLocale;
export type DesignSystemColorScheme = 'light' | 'dark';

export interface DesignSystemProviderProps {
  readonly children: ReactNode;
  readonly locale?: DesignSystemLocale;
  readonly tenantBrand?: TenantBrandProfile;
  readonly onTenantBrandRejected?: (reason: string) => void;
  /** 仅供私有展示册与自动测试固定渲染；产品界面不得将其连接为手动主题开关。 */
  readonly forcedColorScheme?: DesignSystemColorScheme;
}

const sharedRootStyle = {
  '--sf-font-system': semanticTokens.font.system,
  '--sf-font-monospace': semanticTokens.font.monospace,
  '--sf-space-xs': semanticTokens.spacing.xs,
  '--sf-space-sm': semanticTokens.spacing.sm,
  '--sf-space-md': semanticTokens.spacing.md,
  '--sf-space-lg': semanticTokens.spacing.lg,
  '--sf-space-xl': semanticTokens.spacing.xl,
  '--sf-space-xxl': semanticTokens.spacing.xxl,
} as CSSProperties;

const colorSchemeQuery = '(prefers-color-scheme: dark)';
const DesignSystemLocaleContext = createContext<DesignSystemLocale>('zh-CN');
const componentLocaleAdapters = {
  'zh-CN': zhCN,
  'en-US': enUS,
} satisfies Record<SupportedLocale, Locale>;

export function useDesignSystemLocale(): DesignSystemLocale {
  return useContext(DesignSystemLocaleContext);
}

function subscribeToSystemColorScheme(listener: () => void) {
  if (typeof window.matchMedia !== 'function') {
    return () => undefined;
  }
  const query = window.matchMedia(colorSchemeQuery);
  query.addEventListener('change', listener);
  return () => {
    query.removeEventListener('change', listener);
  };
}

function readSystemColorScheme(): DesignSystemColorScheme {
  if (typeof window.matchMedia !== 'function') {
    return 'light';
  }
  return window.matchMedia(colorSchemeQuery).matches ? 'dark' : 'light';
}

/**
 * 安装 SaaS Forge 唯一主题和全局样式边界。应用 Shell 必须只安装一次本 Provider。
 */
export function DesignSystemProvider({
  children,
  locale = 'zh-CN',
  tenantBrand,
  onTenantBrandRejected,
  forcedColorScheme,
}: DesignSystemProviderProps) {
  const systemColorScheme = useSyncExternalStore<DesignSystemColorScheme>(
    subscribeToSystemColorScheme,
    readSystemColorScheme,
    () => 'light' as const,
  );
  const colorScheme = forcedColorScheme ?? systemColorScheme;
  const brandResolution = useMemo(
    () => (tenantBrand === undefined ? undefined : resolveTenantBrandProfile(tenantBrand)),
    [tenantBrand],
  );
  const acceptedBrand = brandResolution?.accepted === true ? brandResolution : undefined;
  const palette = semanticTokens.color[colorScheme];
  const brandTheme = acceptedBrand?.[colorScheme];
  const platformTheme = platformBrandTokenSet[colorScheme];
  const primary = brandTheme?.primary.color ?? platformTheme.primary.color;
  const primaryForeground = brandTheme?.primary.foreground ?? platformTheme.primary.foreground;
  const accent = brandTheme?.accent.color ?? platformTheme.accent.color;
  const accentForeground = brandTheme?.accent.foreground ?? platformTheme.accent.foreground;

  useEffect(() => {
    if (brandResolution?.accepted === false) {
      onTenantBrandRejected?.(brandResolution.reason);
    }
  }, [brandResolution, onTenantBrandRejected]);

  const theme = useMemo<ThemeConfig>(
    () => ({
      algorithm: colorScheme === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
      token: {
        colorPrimary: primary,
        colorBgBase: palette.surface,
        colorTextBase: palette.text,
        colorTextPlaceholder: palette.textSecondary,
        fontFamily: semanticTokens.font.system,
        borderRadius: 6,
        marginXXS: 4,
        marginXS: 8,
        marginSM: 12,
        margin: 16,
        marginLG: 24,
        marginXL: 32,
        paddingXXS: 4,
        paddingXS: 8,
        paddingSM: 12,
        padding: 16,
        paddingLG: 24,
        paddingXL: 32,
      },
    }),
    [colorScheme, palette, primary],
  );
  const rootStyle = {
    ...sharedRootStyle,
    colorScheme,
    '--sf-color-primary': primary,
    '--sf-color-primary-foreground': primaryForeground,
    '--sf-color-accent': accent,
    '--sf-color-accent-foreground': accentForeground,
    '--sf-color-surface': palette.surface,
    '--sf-color-surface-elevated': palette.surfaceElevated,
    '--sf-color-text': palette.text,
    '--sf-color-text-secondary': palette.textSecondary,
    '--sf-color-border': palette.border,
    '--sf-color-success': semanticTokens.color.status.success,
    '--sf-color-warning': semanticTokens.color.status.warning,
    '--sf-color-danger': semanticTokens.color.status.danger,
  } as CSSProperties;

  return (
    <DesignSystemLocaleContext.Provider value={locale}>
      <ConfigProvider
        button={{ autoInsertSpace: false }}
        locale={componentLocaleAdapters[locale]}
        theme={theme}
      >
        <div
          className="sf-design-system-root"
          style={rootStyle}
          lang={locale}
          data-color-scheme={colorScheme}
          data-brand={acceptedBrand === undefined ? 'platform' : 'tenant'}
        >
          {children}
        </div>
      </ConfigProvider>
    </DesignSystemLocaleContext.Provider>
  );
}
