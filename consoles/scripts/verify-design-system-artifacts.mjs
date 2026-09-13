import { createHash } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

import { designSystemDependencyReport } from './check-design-system-boundaries.mjs';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const consumerArtifacts = [
  // Tenant 页面正式使用表格、筛选空态和草稿离开确认；其余消费者仍禁止这些模块。
  { name: 'Platform Console', directory: 'platform-console/dist', usesTenantForms: true },
  { name: 'Tenant Console Shell', directory: 'tenant-console-shell/dist' },
  {
    name: 'Remote 消费夹具',
    directory: 'business-remotes/design-system-consumer-fixture/dist',
  },
];
const unusedAdvancedComponentMarkers = ['已清除当前页选择。', '放弃修改', '重置筛选条件'];

const dependencyReport = await designSystemDependencyReport(consoleRoot);
const versions = new Set(dependencyReport.map(({ resolved }) => resolved));
if (versions.size !== 1) {
  throw new Error('三个消费者没有解析到完全相同的 Design System 版本。');
}

const designSystemDirectory = path.join(consoleRoot, 'shared/design-system/dist');
const designSystemAssets = await filesRecursively(designSystemDirectory);
for (const assetName of ['platform-logo', 'platform-favicon']) {
  if (!designSystemAssets.includes(path.join(designSystemDirectory, `${assetName}.svg`))) {
    throw new Error(`Design System 构建产物缺少版本化平台品牌素材：${assetName}`);
  }
}

const rows = [];
const styleHashes = new Set();
for (const artifact of consumerArtifacts) {
  const directory = path.join(consoleRoot, artifact.directory);
  const assets = await filesRecursively(directory);
  const styles = assets.filter((file) => file.endsWith('.css'));
  if (styles.length !== 1) {
    throw new Error(`${artifact.name} 构建产物必须且只能包含一个全局 CSS 入口。`);
  }
  const style = await readFile(styles[0]);
  styleHashes.add(createHash('sha256').update(style).digest('hex'));
  const scripts = await Promise.all(
    assets.filter((file) => file.endsWith('.js')).map((file) => readFile(file, 'utf8')),
  );
  const script = scripts.join('\n');
  for (const marker of artifact.usesTenantForms ? [] : unusedAdvancedComponentMarkers) {
    if (script.includes(marker)) {
      throw new Error(`${artifact.name} 首屏包含未使用高级公共组件标记：${marker}`);
    }
  }
  rows.push(await artifactSizeRow(artifact.name, assets));
}

if (styleHashes.size !== 1) {
  throw new Error('三个消费者的全局样式产物不一致。');
}

const designSystemFiles = [
  path.join(designSystemDirectory, 'index.js'),
  path.join(designSystemDirectory, 'index.css'),
];
rows.unshift(await artifactSizeRow('Design System 正式包', designSystemFiles));

console.log(`Design System 消费版本：${[...versions][0]}`);
console.log('制品 | 原始字节 | gzip 字节');
for (const row of rows) {
  console.log(`${row.name} | ${String(row.rawBytes)} | ${String(row.gzipBytes)}`);
}
console.log('三个消费者均只有一个、且内容完全相同的全局 CSS 入口。');
console.log('Tenant Console 与 Remote 制品未包含未使用的表格、离开确认或筛选空态实现。');
console.log('Design System 已发布版本化 Platform Logo 与 favicon。');

async function artifactSizeRow(name, files) {
  const bundleFiles = files.filter((file) => file.endsWith('.js') || file.endsWith('.css'));
  let rawBytes = 0;
  let gzipBytes = 0;
  for (const file of bundleFiles) {
    const content = await readFile(file);
    rawBytes += (await stat(file)).size;
    gzipBytes += gzipSync(content).byteLength;
  }
  return { name, rawBytes, gzipBytes };
}

async function filesRecursively(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await filesRecursively(entryPath)));
    } else {
      files.push(entryPath);
    }
  }
  return files;
}
