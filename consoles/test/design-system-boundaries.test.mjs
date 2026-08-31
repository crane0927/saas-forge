import assert from 'node:assert/strict';
import test from 'node:test';

import {
  designSystemDependencyReport,
  findBoundaryViolations,
  forbiddenDeclarationReason,
  forbiddenImportReason,
  forbiddenSelectorReason,
  providerUsageCount,
} from '../scripts/check-design-system-boundaries.mjs';

test('rejects direct Ant Design imports from a Console', () => {
  assert.match(forbiddenImportReason('antd'), /design-system/);
  assert.match(forbiddenImportReason('antd/es/button'), /design-system/);
});

test('rejects unpublished Design System subpaths while allowing the public root', () => {
  assert.equal(forbiddenImportReason('@saas-forge/design-system'), undefined);
  assert.match(forbiddenImportReason('@saas-forge/design-system/src/tokens'), /公共根入口/);
});

test('rejects consumer global styles and public component selector overrides', () => {
  assert.match(forbiddenImportReason('./global.css'), /全局样式/);
  assert.equal(forbiddenImportReason('./tenant-page.module.css'), undefined);
  assert.match(forbiddenSelectorReason('.ant-btn'), /Ant Design 内部选择器/);
  assert.match(forbiddenSelectorReason('.tenant-page .sf-button'), /公共组件内部选择器/);
  assert.equal(forbiddenSelectorReason('.tenant-page__summary'), undefined);
});

test('rejects duplicate public components and detects repeated Theme Providers', () => {
  assert.match(forbiddenDeclarationReason('ServerTable'), /不得重复实现/);
  assert.equal(forbiddenDeclarationReason('TenantSummary'), undefined);
  assert.equal(
    providerUsageCount(`
      <DesignSystemProvider>
        <DesignSystemProvider><App /></DesignSystemProvider>
      </DesignSystemProvider>
    `),
    2,
  );
});

test('resolves all three consumers to the same workspace Design System version', async () => {
  const report = await designSystemDependencyReport();
  assert.equal(report.length, 3);
  assert.deepEqual(new Set(report.map(({ requested }) => requested)), new Set(['workspace:*']));
  assert.equal(new Set(report.map(({ resolved }) => resolved)).size, 1);
});

test('keeps both Consoles and the Remote fixture inside the Design System boundary', async () => {
  assert.deepEqual(await findBoundaryViolations(), []);
});
