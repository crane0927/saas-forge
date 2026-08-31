import { playwright } from '@vitest/browser-playwright';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const browserName = process.env.SF_BROWSER ?? 'chromium';
const browserChannel = process.env.SF_BROWSER_CHANNEL;

export default defineConfig({
  define: {
    'import.meta.env.SF_VISUAL_SNAPSHOTS': JSON.stringify(
      process.env.SF_VISUAL_SNAPSHOTS ?? 'true',
    ),
  },
  plugins: [react()],
  resolve: {
    dedupe: ['react', 'react-dom'],
  },
  optimizeDeps: {
    include: ['react', 'react-dom', 'react-dom/client'],
  },
  test: {
    include: ['browser-test/**/*.browser.test.tsx'],
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
