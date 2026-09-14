import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';
import UnoCSS from '@unocss/vite';
import presetWind3 from '@unocss/preset-wind3';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue(), UnoCSS({ presets: [presetWind3()] })],
  build: {
    lib: {
      entry: fileURLToPath(new URL('./src/index.ts', import.meta.url)),
      formats: ['es'],
      fileName: 'index',
    },
    rolldownOptions: {
      external: [
        'vue',
        'vue-router',
        'pinia',
        'element-plus',
        '@element-plus/icons-vue',
        '@saas-forge/app-runtime',
      ],
    },
  },
});
