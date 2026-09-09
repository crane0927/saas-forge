import { createHash } from 'node:crypto';
import { cp, mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';

// Vite 输出中的源文件区域注释相对工作目录；固定目录保证从任意入口构建字节一致。
process.chdir(fileURLToPath(new URL('../../', import.meta.url)));

const checksums = JSON.parse(
  await readFile(new URL('../static-remote-acceptance/checksums.json', import.meta.url), 'utf8'),
);
const temporary = await mkdtemp(path.join(os.tmpdir(), 'sf-static-remote-build-'));
try {
  // 先验证全部版本，再交付；旧路径内容冻结，工具链变化也不能静默改变已发布字节。
  for (const [version, files] of Object.entries(checksums)) {
    const root = fileURLToPath(new URL(`../static-remote-acceptance/${version}/`, import.meta.url));
    const outDir = path.join(temporary, version);
    await build({
      configFile: false,
      root,
      publicDir: 'public',
      build: {
        outDir,
        emptyOutDir: true,
        lib: { entry: `${root}remote.ts`, formats: ['es'], fileName: () => 'remote.js' },
      },
    });
    for (const [file, expected] of Object.entries(files)) {
      const actual = createHash('sha256')
        .update(await readFile(path.join(outDir, file)))
        .digest('hex');
      if (actual !== expected)
        throw new Error(`${version}/${file}: frozen artifact changed; use a new version path`);
    }
  }
  const destination = new URL('../dist/static-remote-acceptance/', import.meta.url);
  await mkdir(destination, { recursive: true });
  await cp(temporary, destination, { recursive: true });
} finally {
  await rm(temporary, { recursive: true, force: true });
}
