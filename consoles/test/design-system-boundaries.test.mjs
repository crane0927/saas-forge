import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
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
  assert.match(forbiddenImportReason('antd/es/grid'), /design-system/);
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
  assert.match(forbiddenSelectorReason('.remote .sf-responsive-grid'), /公共组件内部选择器/);
  assert.match(forbiddenSelectorReason('.remote .sf-split-layout'), /公共组件内部选择器/);
  assert.equal(forbiddenSelectorReason('.tenant-page__summary'), undefined);
});

test('rejects duplicate public components and detects repeated Theme Providers', () => {
  assert.match(forbiddenDeclarationReason('ApplicationFatalError'), /不得重复实现/);
  assert.match(forbiddenDeclarationReason('PageLayout'), /不得重复实现/);
  assert.match(forbiddenDeclarationReason('ResponsiveGrid'), /不得重复实现/);
  assert.match(forbiddenDeclarationReason('ServerTable'), /不得重复实现/);
  assert.match(forbiddenDeclarationReason('SplitLayout'), /不得重复实现/);
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

test('automatically checks a newly added official Remote', async (context) => {
  const workspaceRoot = await createBoundaryWorkspace();
  context.after(() => rm(workspaceRoot, { recursive: true, force: true }));
  await writePackage(
    workspaceRoot,
    'business-remotes/project-remote',
    {
      name: '@saas-forge/project-remote',
      dependencies: {
        '@saas-forge/design-system': 'workspace:*',
      },
    },
    {
      'src/remote.tsx': "import { Button } from 'antd';\nexport const ProjectRemote = Button;\n",
    },
  );

  const violations = await findBoundaryViolations(workspaceRoot);

  assert.ok(
    violations.some(
      (violation) =>
        violation.includes('business-remotes/project-remote') &&
        violation.includes('只能通过 @saas-forge/design-system'),
    ),
    `新增 Remote 未被自动检查：\n${violations.join('\n')}`,
  );
});

test('automatically checks a newly added Console', async (context) => {
  const workspaceRoot = await createBoundaryWorkspace();
  context.after(() => rm(workspaceRoot, { recursive: true, force: true }));
  await writePackage(
    workspaceRoot,
    'operations-console',
    {
      name: '@saas-forge/operations-console',
      dependencies: {
        '@saas-forge/design-system': 'workspace:*',
      },
    },
    {
      'src/main.tsx':
        "import { Button } from 'antd';\n<DesignSystemProvider><Button /></DesignSystemProvider>;\n",
    },
  );

  const violations = await findBoundaryViolations(workspaceRoot);

  assert.ok(
    violations.some(
      (violation) =>
        violation.includes('operations-console') &&
        violation.includes('只能通过 @saas-forge/design-system'),
    ),
    `新增 Console 未被自动检查：\n${violations.join('\n')}`,
  );
});

test('rejects Ant Design from every consumer dependency section', async (context) => {
  const workspaceRoot = await createBoundaryWorkspace();
  context.after(() => rm(workspaceRoot, { recursive: true, force: true }));
  const dependencySections = [
    ['dependencies', 'runtime-remote'],
    ['devDependencies', 'development-remote'],
    ['peerDependencies', 'peer-remote'],
    ['optionalDependencies', 'optional-remote'],
  ];
  for (const [section, packageName] of dependencySections) {
    const manifest = {
      name: `@saas-forge/${packageName}`,
      dependencies: { '@saas-forge/design-system': 'workspace:*' },
    };
    manifest[section] = { ...manifest[section], antd: '6.6.2' };
    await writePackage(workspaceRoot, `business-remotes/${packageName}`, manifest, {
      'src/remote.tsx': 'export const Remote = () => null;\n',
    });
  }

  const violations = await findBoundaryViolations(workspaceRoot);

  for (const [, packageName] of dependencySections) {
    assert.ok(
      violations.some(
        (violation) =>
          violation.includes(`business-remotes/${packageName}/package.json`) &&
          violation.includes('不得声明 antd 依赖'),
      ),
      `${packageName} 的 Ant Design 依赖未被拒绝：\n${violations.join('\n')}`,
    );
  }
});

async function createBoundaryWorkspace() {
  const workspaceRoot = await mkdtemp(path.join(os.tmpdir(), 'saas-forge-design-system-boundary-'));
  await writePackage(workspaceRoot, 'shared/design-system', {
    name: '@saas-forge/design-system',
    version: '0.1.0',
  });
  await writePackage(
    workspaceRoot,
    'platform-console',
    {
      name: '@saas-forge/platform-console',
      dependencies: { '@saas-forge/design-system': 'workspace:*' },
    },
    { 'src/main.tsx': '<DesignSystemProvider><App /></DesignSystemProvider>\n' },
  );
  await writePackage(
    workspaceRoot,
    'tenant-console-shell',
    {
      name: '@saas-forge/tenant-console-shell',
      dependencies: { '@saas-forge/design-system': 'workspace:*' },
    },
    { 'src/main.tsx': '<DesignSystemProvider><App /></DesignSystemProvider>\n' },
  );
  await writePackage(
    workspaceRoot,
    'business-remotes/design-system-consumer-fixture',
    {
      name: '@saas-forge/design-system-consumer-fixture',
      dependencies: { '@saas-forge/design-system': 'workspace:*' },
    },
    {
      'host/main.tsx': '<DesignSystemProvider><App /></DesignSystemProvider>\n',
      'src/remote.tsx': 'export const Remote = () => null;\n',
    },
  );
  return workspaceRoot;
}

async function writePackage(workspaceRoot, packageRoot, manifest, files = {}) {
  const absolutePackageRoot = path.join(workspaceRoot, packageRoot);
  await mkdir(absolutePackageRoot, { recursive: true });
  await writeFile(
    path.join(absolutePackageRoot, 'package.json'),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  for (const [relativeFile, content] of Object.entries(files)) {
    const absoluteFile = path.join(absolutePackageRoot, relativeFile);
    await mkdir(path.dirname(absoluteFile), { recursive: true });
    await writeFile(absoluteFile, content);
  }
}
