export const semanticTokens = {
  color: {
    platformPrimary: '#2563EB',
    platformAccent: '#C026D3',
    light: {
      surface: '#FFFFFF',
      layout: '#F5F6F8',
      controlBorder: '#8490A3',
      surfaceElevated: '#F8FAFC',
      text: '#172033',
      textSecondary: '#667085',
      border: '#E5E8ED',
    },
    dark: {
      surface: '#1A202A',
      layout: '#11151C',
      controlBorder: '#758399',
      surfaceElevated: '#202834',
      text: '#E8EDF6',
      textSecondary: '#A3AFC0',
      border: '#303A49',
    },
    statusDark: { success: '#80D9A3', warning: '#EFC275', danger: '#F49C9C' },
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
