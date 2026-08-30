import eslint from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: ['**/.generated/**', '**/dist/**', '**/node_modules/**'],
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
            {
              group: ['antd', 'antd/*'],
              message: 'Console 必须通过 @saas-forge/design-system 使用 Ant Design。',
            },
            {
              group: ['@saas-forge/design-system/*'],
              message: '消费者只能从 @saas-forge/design-system 公共根入口导入。',
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
  {
    files: ['shared/design-system/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/.generated', '**/.generated/**'],
              message: '手写代码必须通过 @saas-forge/api-client 的公开入口消费生成 Client。',
            },
            {
              group: ['@saas-forge/design-system/*'],
              message: 'Design System 内部必须使用相对路径，不得反向消费未公开子路径。',
            },
          ],
        },
      ],
    },
  },
);
