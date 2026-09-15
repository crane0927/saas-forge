import eslint from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import vue from 'eslint-plugin-vue';
const ui = [
  'shared/admin/**',
  'platform-console/**',
  'tenant-console-shell/**',
  'business-remotes/**',
  'browser-test/**',
  'static-remote-acceptance/**',
];
export default tseslint.config(
  {
    ignores: ['**/.generated/**', '**/dist/**', '**/node_modules/**', 'shared/admin/src/vendor/**'],
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    ...eslint.configs.recommended,
    languageOptions: { globals: globals.node },
  },
  ...tseslint.configs.strictTypeChecked.map((c) => ({
    ...c,
    files: ['shared/{app-runtime,api-client,i18n}/**/*.ts'],
    languageOptions: {
      ...c.languageOptions,
      parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
    },
  })),
  ...tseslint.configs.recommended.map((c) => ({ ...c, files: ui.map((p) => p + '/*.{ts,vue}') })),
  {
    files: ['*.ts', 'static-remote-acceptance/**/*.ts'],
    languageOptions: { parser: tseslint.parser },
  },
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/.generated', '**/.generated/**'],
              message: '生成 Client 只能通过公共 API 入口消费。',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser, extraFileExtensions: ['.vue'] } },
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/html-indent': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
    },
  },
  { files: ['shared/api-client/src/index.ts'], rules: { 'no-restricted-imports': 'off' } },
);
