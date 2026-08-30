import eslint from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['**/.generated/**', '**/node_modules/**'],
  },
  {
    files: ['**/*.{js,mjs,cjs}'],
    ...eslint.configs.recommended,
    languageOptions: {
      globals: globals.node,
    },
  },
  ...tseslint.configs.strictTypeChecked.map((config) => ({
    ...config,
    files: ['**/*.{ts,tsx}'],
  })),
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/.generated', '**/.generated/**'],
              message: '手写代码必须通过 @saas-forge/api-client 的公开入口消费生成 Client。',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['shared/api-client/src/index.ts'],
    rules: {
      'no-restricted-imports': 'off',
    },
  },
);
