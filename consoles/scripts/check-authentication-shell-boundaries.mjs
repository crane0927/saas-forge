import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { files, findBoundaryViolations } from './check-admin-boundaries.mjs';
export async function findAuthenticationShellBoundaryViolations(
  root = fileURLToPath(new URL('..', import.meta.url)),
) {
  const errors = await findBoundaryViolations(root);
  let creation = 0;
  for (const file of await files(path.join(root, 'shared/admin/src'))) {
    if (!/\.(ts|vue)$/.test(file)) continue;
    const source = await readFile(file, 'utf8');
    creation += (source.match(/createAuthenticationRuntimeAfterConfig\s*\(/g) ?? []).length;
    if (/Authorization|X-SF-CSRF|\bCookie\b|new\s+AuthenticationApi/.test(source))
      errors.push(`${file}: 共享 UI 不得管理会话凭据`);
    if (
      /\bfetch\s*\(/.test(source) &&
      !file.endsWith('/application/ConsoleApplication.vue') &&
      !file.endsWith('/brand/resolved-brand.ts')
    )
      errors.push(`${file}: 非法 HTTP 边界`);
  }
  if (creation !== 1) errors.push('共享 ConsoleApplication 必须只创建一个 Runtime');
  return errors;
}
