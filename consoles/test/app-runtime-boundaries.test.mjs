import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const workspaceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = path.join(workspaceRoot, 'shared/app-runtime');

test('keeps app-runtime free of React, UI, and unpublished entrypoints', async () => {
  const manifest = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
  const dependencyNames = Object.keys(manifest.dependencies ?? {});
  assert.deepEqual(
    dependencyNames,
    ['@saas-forge/api-client'],
    'app-runtime 只能依赖生成 API Client，不得拥有 React、UI 或 Design System',
  );
  assert.deepEqual(Object.keys(manifest.exports), ['.'], 'app-runtime 只能暴露公共根入口');

  const sourceFiles = await listFiles(path.join(packageRoot, 'src'));
  assert.ok(
    sourceFiles.every((file) => file.endsWith('.ts')),
    'app-runtime 不得包含 TSX/UI 源文件',
  );
  for (const sourceFile of sourceFiles) {
    const source = await readFile(sourceFile, 'utf8');
    assert.doesNotMatch(source, /from\s+['"](?:react|react-dom|@saas-forge\/design-system)/);
  }
});

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(
    entries.map((entry) => {
      const absolutePath = path.join(directory, entry.name);
      return entry.isDirectory() ? listFiles(absolutePath) : [absolutePath];
    }),
  );
  return files.flat();
}
