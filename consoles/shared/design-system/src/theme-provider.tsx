import { ConfigProvider, type ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { CSSProperties, ReactNode } from 'react';

import { semanticTokens } from './tokens';

interface DesignSystemProviderProps {
  readonly children: ReactNode;
}

const theme: ThemeConfig = {
  token: {
    colorPrimary: semanticTokens.color.platformPrimary,
    colorBgBase: semanticTokens.color.surface,
    colorTextBase: semanticTokens.color.text,
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
};

const rootStyle = {
  '--sf-color-primary': semanticTokens.color.platformPrimary,
  '--sf-color-surface': semanticTokens.color.surface,
  '--sf-color-text': semanticTokens.color.text,
  '--sf-color-text-secondary': semanticTokens.color.textSecondary,
  '--sf-color-border': semanticTokens.color.border,
  '--sf-font-system': semanticTokens.font.system,
  '--sf-font-monospace': semanticTokens.font.monospace,
  '--sf-space-xs': semanticTokens.spacing.xs,
  '--sf-space-sm': semanticTokens.spacing.sm,
  '--sf-space-md': semanticTokens.spacing.md,
  '--sf-space-lg': semanticTokens.spacing.lg,
  '--sf-space-xl': semanticTokens.spacing.xl,
  '--sf-space-xxl': semanticTokens.spacing.xxl,
} as CSSProperties;

/**
 * 安装 SaaS Forge 唯一主题和全局样式边界。应用 Shell 必须只安装一次本 Provider。
 */
export function DesignSystemProvider({ children }: DesignSystemProviderProps) {
  return (
    <ConfigProvider button={{ autoInsertSpace: false }} locale={zhCN} theme={theme}>
      <div className="sf-design-system-root" style={rootStyle}>
        {children}
      </div>
    </ConfigProvider>
  );
}
