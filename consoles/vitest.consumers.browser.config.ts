import { playwright } from '@vitest/browser-playwright';
import vue from '@vitejs/plugin-vue';
import UnoCSS from '@unocss/vite';
import presetWind3 from '@unocss/preset-wind3';
import { defineConfig } from 'vitest/config';

const browserName = process.env.SF_BROWSER ?? 'chromium';
const browserChannel = process.env.SF_BROWSER_CHANNEL;

export default defineConfig({
  publicDir: 'browser-test/public',
  define: {
    'import.meta.env.SF_MACOS_WEBKIT': JSON.stringify(
      browserName === 'webkit' && process.platform === 'darwin',
    ),
    'import.meta.env.SF_VISUAL_SNAPSHOTS': JSON.stringify(
      process.env.SF_VISUAL_SNAPSHOTS ?? 'false',
    ),
  },
  plugins: [
    vue(),
    UnoCSS({
      presets: [presetWind3()],
      content: { filesystem: ['shared/admin/src/**/*.vue', 'shared/admin/test/**/*.vue'] },
    }),
  ],
  resolve: {
    dedupe: ['vue'],
  },
  optimizeDeps: {
    include: ['vue', 'element-plus'],
  },
  test: {
    include: ['browser-test/**/*.browser.test.ts'],
    browser: {
      enabled: true,
      headless: true,
      provider: playwright({
        contextOptions: { reducedMotion: 'reduce' },
        launchOptions: browserChannel === undefined ? undefined : { channel: browserChannel },
      }),
      instances: [
        {
          browser: browserName as 'chromium' | 'firefox' | 'webkit',
          viewport: { width: 1280, height: 900 },
        },
      ],
      expect: {
        toMatchScreenshot: {
          comparatorName: 'pixelmatch',
          comparatorOptions: {
            threshold: 0.2,
            allowedMismatchedPixelRatio: 0.001,
          },
        },
      },
    },
  },
});
