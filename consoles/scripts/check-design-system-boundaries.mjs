import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import ts from 'typescript';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const consumerRoots = ['platform-console', 'tenant-console-shell'];
const sourceExtensions = new Set(['.js', '.jsx', '.mjs', '.ts', '.tsx']);

export function forbiddenImportReason(specifier) {
  if (specifier === 'antd' || specifier.startsWith('antd/')) {
    return 'Console 只能通过 @saas-forge/design-system 使用 Ant Design。';
  }
  if (specifier.startsWith('@saas-forge/design-system/')) {
    return 'Console 只能从 @saas-forge/design-system 公共根入口导入。';
  }
  return undefined;
}

export async function findBoundaryViolations(root = consoleRoot) {
  const violations = [];

  for (const consumer of consumerRoots) {
    const consumerRoot = path.join(root, consumer);
    const packageManifest = JSON.parse(
      await readFile(path.join(consumerRoot, 'package.json'), 'utf8'),
    );
    if (packageManifest.dependencies?.antd !== undefined) {
      violations.push(`${consumer}/package.json: Console 不得声明 antd 依赖。`);
    }

    for (const file of await sourceFiles(consumerRoot)) {
      const source = await readFile(file, 'utf8');
      for (const imported of ts.preProcessFile(source, true, true).importedFiles) {
        const reason = forbiddenImportReason(imported.fileName);
        if (reason !== undefined) {
          violations.push(
            `${path.relative(root, file)}:${sourceLine(source, imported.pos)}: ${reason} (${imported.fileName})`,
          );
        }
      }
    }
  }

  return violations;
}

async function sourceFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'dist' && entry.name !== 'node_modules') {
        files.push(...(await sourceFiles(entryPath)));
      }
      continue;
    }
    if (sourceExtensions.has(path.extname(entry.name))) {
      files.push(entryPath);
    }
  }
  return files;
}

function sourceLine(source, offset) {
  return source.slice(0, offset).split('\n').length;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const violations = await findBoundaryViolations();
  if (violations.length > 0) {
    console.error(violations.join('\n'));
    process.exitCode = 1;
  }
}
