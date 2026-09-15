import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { files, adminDependencyReport } from './check-admin-boundaries.mjs';
const root = fileURLToPath(new URL('..', import.meta.url));
const report = await adminDependencyReport(root);
if (
  new Set(report.map((r) => r.vue)).size !== 1 ||
  new Set(report.map((r) => r.elementPlus)).size !== 1
)
  throw new Error('消费者依赖版本不一致');
for (const { directory } of report) {
  const artifacts = await files(path.join(root, directory, 'dist/assets'));
  const scripts = artifacts.filter((p) => p.endsWith('.js'));
  if (!scripts.length) throw new Error(`${directory}: 缺少构建产物`);
  const content = (await Promise.all(scripts.map((p) => readFile(p, 'utf8')))).join('\n');
  if (/react-dom|react\.production|ant-design|ant-btn|sf-design-system-root/.test(content))
    throw new Error(`${directory}: 旧 UI 混入产物`);
  const css = artifacts.filter((p) => p.endsWith('.css'));
  if (!css.length) throw new Error(`${directory}: 缺少样式`);
  if (directory.includes('console'))
    for (const name of ['platform-logo', 'platform-favicon'])
      if (!artifacts.some((p) => path.basename(p).startsWith(name)))
        throw new Error(`${directory}: 缺少平台品牌素材 ${name}`);
  if (
    directory.startsWith('business-remotes') &&
    /tenantSwitchCommittedTitle|creationRecoveryTitle|sessionSlot|Idempotency-Key/.test(content)
  )
    throw new Error('Remote 首屏混入未使用的认证或恢复模块');
  console.log(`${directory}: ${scripts.length} JS chunks, gzip ${gzipSync(content).length} bytes`);
}
