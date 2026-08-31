import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import ts from 'typescript';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const designSystemPackage = 'shared/design-system';
const dependencySections = [
  'dependencies',
  'devDependencies',
  'peerDependencies',
  'optionalDependencies',
];
const sourceExtensions = new Set(['.js', '.jsx', '.mjs', '.ts', '.tsx']);
const publicComponentNames = new Set([
  'ActionMenu',
  'ApplicationFatalError',
  'ApplicationLoading',
  'Button',
  'CheckboxField',
  'ConfigurationFailure',
  'EmptyDataState',
  'FieldError',
  'FilteredEmptyState',
  'FormErrorSummary',
  'FormLayout',
  'FormRow',
  'InitialContentLoading',
  'IrreversibleDangerDialog',
  'Link',
  'LoadFailureState',
  'NotFoundState',
  'PageLayout',
  'PageTitle',
  'PasswordField',
  'PersistentError',
  'RecoverableDangerDialog',
  'RefreshingContent',
  'ResponsiveGrid',
  'SelectField',
  'ServerTable',
  'SplitLayout',
  'StandardDialog',
  'SuccessFeedback',
  'TextField',
  'UnsavedChangesDialog',
  'WarningFeedback',
]);

export function forbiddenImportReason(specifier) {
  if (specifier === 'antd' || specifier.startsWith('antd/')) {
    return '消费者只能通过 @saas-forge/design-system 使用 Ant Design。';
  }
  if (specifier.startsWith('@saas-forge/design-system/')) {
    return '消费者只能从 @saas-forge/design-system 公共根入口导入。';
  }
  if (
    /\.(?:css|less|sass|scss)$/.test(specifier) &&
    !/\.module\.(?:css|less|sass|scss)$/.test(specifier)
  ) {
    return '消费者不得导入全局样式；唯一全局样式由 Design System 公共入口安装。';
  }
  return undefined;
}

export function forbiddenSelectorReason(selector) {
  if (/(?:^|[\s>+~,])\.ant-/.test(selector)) {
    return '消费者不得覆盖 Ant Design 内部选择器。';
  }
  if (
    /(?:^|[\s>+~,])\.sf-(?:design-system-root|button|dialog|field|form|page|responsive-grid|server-table|split-layout)/.test(
      selector,
    )
  ) {
    return '消费者不得覆盖 Design System 公共组件内部选择器。';
  }
  return undefined;
}

export function forbiddenDeclarationReason(name) {
  return publicComponentNames.has(name)
    ? `消费者不得重复实现已有公共组件 ${name}；应先扩展 Design System。`
    : undefined;
}

export async function designSystemDependencyReport(root = consoleRoot) {
  const designSystemManifest = await readManifest(path.join(root, designSystemPackage));
  const consumers = await discoverConsumers(root);
  return Promise.all(
    consumers.map(async (consumer) => {
      const manifest = await readManifest(path.join(root, consumer.packageRoot));
      return {
        consumer: manifest.name,
        requested: manifest.dependencies?.['@saas-forge/design-system'],
        resolved: designSystemManifest.version,
      };
    }),
  );
}

export async function findBoundaryViolations(root = consoleRoot) {
  const violations = [];
  const consumers = await discoverConsumers(root);
  const dependencyReport = await designSystemDependencyReport(root);

  for (const dependency of dependencyReport) {
    if (dependency.requested !== 'workspace:*') {
      violations.push(
        `${dependency.consumer}/package.json: Design System 必须使用 workspace:*，当前为 ${String(dependency.requested)}。`,
      );
    }
  }

  if (new Set(dependencyReport.map(({ resolved }) => resolved)).size !== 1) {
    violations.push('三个消费者没有解析到完全相同的 Design System 版本。');
  }

  for (const consumer of consumers) {
    const packageRoot = path.join(root, consumer.packageRoot);
    const manifest = await readManifest(packageRoot);
    if (dependencySections.some((section) => manifest[section]?.antd !== undefined)) {
      violations.push(`${consumer.packageRoot}/package.json: 消费者不得声明 antd 依赖。`);
    }

    for (const runtimeRoot of consumer.runtimeRoots) {
      const absoluteRuntimeRoot = path.join(packageRoot, runtimeRoot);
      for (const file of await filesRecursively(absoluteRuntimeRoot)) {
        const relativeFile = path.relative(root, file);
        const extension = path.extname(file);
        const source = await readFile(file, 'utf8');

        if (sourceExtensions.has(extension)) {
          const sourceFile = ts.createSourceFile(
            file,
            source,
            ts.ScriptTarget.Latest,
            true,
            extension === '.tsx' || extension === '.jsx' ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
          );
          for (const imported of ts.preProcessFile(source, true, true).importedFiles) {
            const reason = forbiddenImportReason(imported.fileName);
            if (reason !== undefined) {
              violations.push(
                `${relativeFile}:${sourceLine(source, imported.pos)}: ${reason} (${imported.fileName})`,
              );
            }
          }
          inspectDeclarations(sourceFile, relativeFile, violations);
        }

        if (/\.(?:css|less|sass|scss)$/.test(extension)) {
          inspectSelectors(source, relativeFile, violations);
        }
      }
    }

    if (consumer.providerEntry !== undefined) {
      const providerEntry = path.join(packageRoot, consumer.providerEntry);
      const providerEntrySource = await readFile(providerEntry, 'utf8');
      if (providerUsageCount(providerEntrySource, providerEntry) !== 1) {
        violations.push(
          `${path.relative(root, providerEntry)}: Shell 入口必须且只能安装一个 DesignSystemProvider。`,
        );
      }
    }

    for (const forbiddenRoot of consumer.providerForbiddenRoots ?? []) {
      const absoluteForbiddenRoot = path.join(packageRoot, forbiddenRoot);
      for (const file of await sourceFiles(absoluteForbiddenRoot)) {
        const source = await readFile(file, 'utf8');
        if (providerUsageCount(source, file) > 0 || source.includes('DesignSystemProvider')) {
          violations.push(
            `${path.relative(root, file)}: Remote 必须继承 Shell 主题，不得安装 DesignSystemProvider。`,
          );
        }
      }
    }
  }

  return violations;
}

async function discoverConsumers(root) {
  const consoleConsumers = [];
  for (const entry of await readdir(root, { withFileTypes: true })) {
    if (
      !entry.isDirectory() ||
      entry.name === 'business-remotes' ||
      entry.name === 'node_modules' ||
      entry.name === 'shared'
    ) {
      continue;
    }
    const packageEntries = await readdir(path.join(root, entry.name), { withFileTypes: true });
    const entryNames = new Set(packageEntries.map(({ name }) => name));
    if (!entryNames.has('package.json') || !entryNames.has('src')) {
      continue;
    }
    consoleConsumers.push({
      packageRoot: entry.name,
      runtimeRoots: ['src'],
      providerEntry: 'src/main.tsx',
    });
  }

  const remoteParent = path.join(root, 'business-remotes');
  const remoteConsumers = [];
  for (const entry of await readdir(remoteParent, { withFileTypes: true })) {
    if (!entry.isDirectory()) {
      continue;
    }
    const packageRoot = path.join('business-remotes', entry.name);
    const packageEntries = await readdir(path.join(root, packageRoot), { withFileTypes: true });
    const entryNames = new Set(packageEntries.map(({ name }) => name));
    if (!entryNames.has('package.json')) {
      continue;
    }
    const runtimeRoots = ['host', 'src'].filter((runtimeRoot) => entryNames.has(runtimeRoot));
    remoteConsumers.push({
      packageRoot,
      runtimeRoots,
      providerEntry: entryNames.has('host') ? 'host/main.tsx' : undefined,
      providerForbiddenRoots: entryNames.has('src') ? ['src'] : [],
    });
  }
  return [...consoleConsumers, ...remoteConsumers];
}

function inspectDeclarations(sourceFile, relativeFile, violations) {
  const visit = (node) => {
    if (
      (ts.isFunctionDeclaration(node) ||
        ts.isClassDeclaration(node) ||
        ts.isVariableDeclaration(node)) &&
      node.name !== undefined &&
      ts.isIdentifier(node.name)
    ) {
      const reason = forbiddenDeclarationReason(node.name.text);
      if (reason !== undefined) {
        violations.push(
          `${relativeFile}:${sourceFile.getLineAndCharacterOfPosition(node.name.getStart()).line + 1}: ${reason}`,
        );
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
}

function inspectSelectors(source, relativeFile, violations) {
  for (const match of source.matchAll(/([^{}]+)\{/g)) {
    const selector = match[1]?.trim();
    if (selector === undefined || selector.startsWith('@')) {
      continue;
    }
    const reason = forbiddenSelectorReason(selector);
    if (reason !== undefined) {
      violations.push(
        `${relativeFile}:${sourceLine(source, match.index ?? 0)}: ${reason} (${selector})`,
      );
    }
  }
}

export function providerUsageCount(source, file = 'consumer.tsx') {
  const sourceFile = ts.createSourceFile(
    file,
    source,
    ts.ScriptTarget.Latest,
    true,
    file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  let count = 0;
  const visit = (node) => {
    if (
      (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) &&
      ts.isIdentifier(node.tagName) &&
      node.tagName.text === 'DesignSystemProvider'
    ) {
      count += 1;
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  return count;
}

async function readManifest(packageRoot) {
  return JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
}

async function sourceFiles(directory) {
  return (await filesRecursively(directory)).filter((file) =>
    sourceExtensions.has(path.extname(file)),
  );
}

async function filesRecursively(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'dist' && entry.name !== 'node_modules') {
        files.push(...(await filesRecursively(entryPath)));
      }
      continue;
    }
    files.push(entryPath);
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
  } else {
    const report = await designSystemDependencyReport();
    console.log(
      report
        .map(({ consumer, resolved }) => `${consumer}: @saas-forge/design-system@${resolved}`)
        .join('\n'),
    );
  }
}
