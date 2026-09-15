import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  findBoundaryViolations,
  brandBoundaryViolations,
  adminDependencyReport,
} from '../scripts/check-admin-boundaries.mjs';
test('formal consumers use one Vue admin boundary', async () => {
  assert.deepEqual(await findBoundaryViolations(), []);
  assert.equal((await adminDependencyReport()).length, 3);
});
test('brand aliases and direct token writes are rejected', () => {
  for (const source of [
    'const profile=runtime.getState().tenantContext.brandProfile; const name=profile.displayName;',
    'const {brandProfile:{displayName}}=context;',
    "document.title='Other';",
    "root.style.setProperty('--el-color-primary','#123456');",
    "import {resolveBrandProfile as resolve} from '@saas-forge/admin';",
  ]) {
    assert.ok(brandBoundaryViolations(source, 'remote.ts', { remote: true }).length, source);
  }
  assert.deepEqual(
    brandBoundaryViolations(
      "const color=getComputedStyle(root).getPropertyValue('--el-color-primary');",
      'remote.ts',
      { remote: true },
    ),
    [],
  );
});
test('Vue template cannot install a second provider or load brand assets', () => {
  for (const source of [
    '<template><ElConfigProvider/></template>',
    '<template><img src="/brands/a.svg"/></template>',
  ])
    assert.ok(brandBoundaryViolations(source, 'Remote.vue', { remote: true }).length);
});
test('new Console and Remote automatically inherit credential, locale and dependency guards', async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'vue-boundary-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const directory of ['new-console', 'business-remotes/new-remote']) {
    await mkdir(path.join(root, directory, 'src'), { recursive: true });
    await writeFile(
      path.join(root, directory, 'package.json'),
      JSON.stringify({
        dependencies: {
          '@saas-forge/admin': 'workspace:*',
          vue: '3.5.31',
          'element-plus': '2.13.6',
          react: 'old',
        },
      }),
    );
    await writeFile(
      path.join(root, directory, 'src/Invalid.vue'),
      '<script setup>localStorage.setItem("locale","en-US"); fetch("/api",{credentials:"include"});</script>',
    );
  }
  const errors = await findBoundaryViolations(root);
  assert.ok(errors.some((e) => e.includes('new-console') && e.includes('旧 UI')));
  assert.ok(errors.some((e) => e.includes('new-remote') && e.includes('宿主')));
  assert.ok(errors.some((e) => e.includes('不得实现凭据')));
});
