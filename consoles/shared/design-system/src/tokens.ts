export const semanticTokens = {
  color: {
    platformPrimary: '#2563EB',
    light: {
      surface: '#FFFFFF',
      surfaceElevated: '#F8FAFC',
      text: '#0F172A',
      textSecondary: '#475569',
      border: '#CBD5E1',
    },
    dark: {
      surface: '#0F172A',
      surfaceElevated: '#1E293B',
      text: '#F8FAFC',
      textSecondary: '#CBD5E1',
      border: '#475569',
    },
    status: {
      success: '#15803D',
      warning: '#A16207',
      danger: '#B91C1C',
    },
  },
  font: {
    system:
      'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif',
    monospace: 'ui-monospace, "SFMono-Regular", Consolas, "Liberation Mono", monospace',
  },
  spacing: {
    xs: '4px',
    sm: '8px',
    md: '12px',
    lg: '16px',
    xl: '24px',
    xxl: '32px',
  },
} as const;
