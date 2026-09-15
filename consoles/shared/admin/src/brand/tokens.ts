// 品牌颜色校验所需的稳定边界，不包含已退役的 UI 布局 token。
export const semanticTokens = {
  color: {
    platformPrimary: '#2563EB',
    platformAccent: '#C026D3',
    light: { surface: '#FFFFFF' },
    dark: { surface: '#1A202A' },
    status: { success: '#15803D', warning: '#A16207', danger: '#B91C1C' },
  },
} as const;
