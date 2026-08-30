import assert from 'node:assert/strict';
import test from 'node:test';

import {
  findBoundaryViolations,
  forbiddenImportReason,
} from '../scripts/check-design-system-boundaries.mjs';

test('rejects direct Ant Design imports from a Console', () => {
  assert.match(forbiddenImportReason('antd'), /design-system/);
  assert.match(forbiddenImportReason('antd/es/button'), /design-system/);
});

test('rejects unpublished Design System subpaths while allowing the public root', () => {
  assert.equal(forbiddenImportReason('@saas-forge/design-system'), undefined);
  assert.match(forbiddenImportReason('@saas-forge/design-system/src/tokens'), /公共根入口/);
});

test('keeps both Console sources inside the Design System dependency boundary', async () => {
  assert.deepEqual(await findBoundaryViolations(), []);
});
