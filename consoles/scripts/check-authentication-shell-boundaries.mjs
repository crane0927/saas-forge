import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const workspaceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

export async function findAuthenticationShellBoundaryViolations(root = workspaceRoot) {
  const violations = [];
  const shellRoot = path.join(root, 'shared/react-shell');
  const shellManifest = JSON.parse(await readFile(path.join(shellRoot, 'package.json'), 'utf8'));
  const dependencies = Object.keys(shellManifest.dependencies ?? {}).toSorted();
  if (
    JSON.stringify(dependencies) !==
    JSON.stringify(['@saas-forge/app-runtime', '@saas-forge/design-system', '@saas-forge/i18n'])
  ) {
    violations.push('shared/react-shell 只能依赖 app-runtime、design-system 与 i18n');
  }
  if (JSON.stringify(Object.keys(shellManifest.exports ?? {})) !== JSON.stringify(['.'])) {
    violations.push('shared/react-shell 只能暴露公共根入口');
  }

  const allowedShellImports = new Set([
    '@saas-forge/app-runtime',
    '@saas-forge/design-system',
    '@saas-forge/i18n',
    'react',
    'react-router',
  ]);
  for (const sourceFile of await listSourceFiles(path.join(shellRoot, 'src'))) {
    const source = await readFile(sourceFile, 'utf8');
    for (const specifier of importSpecifiers(source)) {
      if (!specifier.startsWith('.') && !allowedShellImports.has(specifier)) {
        violations.push(`${relative(root, sourceFile)} 使用了未允许的依赖 ${specifier}`);
      }
      if (
        specifier.startsWith('@saas-forge/app-runtime/') ||
        specifier.startsWith('@saas-forge/design-system/')
      ) {
        violations.push(`${relative(root, sourceFile)} 必须使用共享包公共根入口`);
      }
    }
    if (/\bfetch\s*\(|credentials\s*:|Authorization|X-SF-CSRF|\bCookie\b/.test(source)) {
      violations.push(`${relative(root, sourceFile)} 不得实现凭据型 HTTP`);
    }
  }

  for (const host of [
    { directory: 'platform-console', name: 'Platform Console' },
    { directory: 'tenant-console-shell', name: 'Tenant Console' },
  ]) {
    const hostRoot = path.join(root, host.directory);
    const hostManifest = JSON.parse(await readFile(path.join(hostRoot, 'package.json'), 'utf8'));
    if (hostManifest.dependencies?.['@saas-forge/react-shell'] !== 'workspace:*') {
      violations.push(`${host.name} 必须消费共享 React Shell`);
    }
    if (hostManifest.dependencies?.['@saas-forge/api-client'] !== undefined) {
      violations.push(`${host.name} 不得直接依赖生成 API Client`);
    }
    let runtimeCreationCount = 0;
    let fetchForwardingCount = 0;
    for (const sourceFile of await listSourceFiles(path.join(hostRoot, 'src'))) {
      const source = await readFile(sourceFile, 'utf8');
      runtimeCreationCount += occurrences(source, 'createAuthenticationRuntimeAfterConfig(');
      fetchForwardingCount += occurrences(source, 'fetch(input, init)');
      if (
        /credentials\s*:|Authorization|X-SF-CSRF|\bCookie\b|new\s+AuthenticationApi|@saas-forge\/api-client/.test(
          source,
        )
      ) {
        violations.push(`${relative(root, sourceFile)} 不得实现第二套认证或凭据请求`);
      }
      if (/class\s+\w*ErrorBoundary\s+extends/.test(source)) {
        violations.push(`${relative(root, sourceFile)} 必须复用共享 React Shell 错误边界`);
      }
    }
    if (runtimeCreationCount !== 1) {
      violations.push(`${host.name} 必须且只能在宿主边界创建一个认证 Runtime`);
    }
    if (fetchForwardingCount !== 1) {
      violations.push(`${host.name} 只能向共享 Runtime 提供一次原生 fetch 转发`);
    }
  }
  return violations;
}

async function listSourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map(async (entry) => {
      const absolutePath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        return listSourceFiles(absolutePath);
      }
      return /\.[cm]?[jt]sx?$/.test(entry.name) ? [absolutePath] : [];
    }),
  );
  return nested.flat();
}

function importSpecifiers(source) {
  return [...source.matchAll(/from\s+['"]([^'"]+)['"]/g)].map((match) => match[1]);
}

function occurrences(source, value) {
  return source.split(value).length - 1;
}

function relative(root, file) {
  return path.relative(root, file);
}
