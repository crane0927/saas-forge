import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { access, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const repository = new URL('../../', import.meta.url);
const generated = new URL('consoles/shared/api-client/.generated/', repository);
const receipt = new URL('.saas-forge-inputs.sha256', generated);
const recovery =
  '请在仓库根目录运行 pnpm --dir consoles run generate:api，然后重新执行 pnpm run dev。';

async function inputsHash() {
  const hash = createHash('sha256');
  for (const file of ['contracts/openapi/v1.yaml', 'contracts/openapi/pom.xml', 'pom.xml']) {
    hash.update(await readFile(new URL(file, repository)));
    hash.update('\0');
  }
  return hash.digest('hex');
}

async function checkFiles() {
  const files = (await readFile(new URL('.openapi-generator/FILES', generated), 'utf8'))
    .split(/\r?\n/u)
    .filter((file) => file.endsWith('.ts'));
  for (const required of ['index.ts', 'runtime.ts', 'apis/index.ts', 'models/index.ts']) {
    if (!files.includes(required)) throw new Error('API Client 生成清单不完整');
  }
  for (const file of files) await access(new URL(file, generated));
}

try {
  if (process.argv[2] === '--generate') {
    await rm(receipt, { force: true });
    // 只有正式生成成功且输入未在生成期间变化，才记录本次准备对应的契约。
    const before = await inputsHash();
    const result = spawnSync(
      fileURLToPath(new URL('mvnw', repository)),
      [
        '-f',
        'pom.xml',
        '--batch-mode',
        '--no-transfer-progress',
        '-pl',
        'contracts/openapi',
        '-am',
        'generate-sources',
      ],
      { cwd: fileURLToPath(repository), stdio: 'inherit' },
    );
    if (result.error || result.status !== 0) throw new Error('正式 API Client 生成失败');
    await checkFiles();
    if (before !== (await inputsHash())) throw new Error('生成期间契约发生变化');
    await writeFile(receipt, before);
    console.info('API Client 已准备完成。');
  } else {
    await checkFiles();
    if ((await readFile(receipt, 'utf8')) !== (await inputsHash())) {
      throw new Error('API Client 已过期');
    }
  }
} catch {
  console.error(`API Client 缺失、过期或生成失败。${recovery}`);
  process.exitCode = 1;
}
