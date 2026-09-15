import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';
const rootDefault = fileURLToPath(new URL('..', import.meta.url));
export async function files(directory) {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch {
    return [];
  }
  return (
    await Promise.all(
      entries
        .filter((e) => !['node_modules', 'dist', '.generated', 'vendor'].includes(e.name))
        .map((e) =>
          e.isDirectory() ? files(path.join(directory, e.name)) : [path.join(directory, e.name)],
        ),
    )
  ).flat();
}
export async function consumers(root = rootDefault) {
  const dirs = await readdir(root, { withFileTypes: true });
  const names = dirs
    .filter((e) => e.isDirectory() && e.name.includes('console'))
    .map((e) => e.name);
  const remoteRoot = path.join(root, 'business-remotes');
  let remotes = [];
  try {
    remotes = (await readdir(remoteRoot, { withFileTypes: true }))
      .filter((e) => e.isDirectory())
      .map((e) => 'business-remotes/' + e.name);
  } catch {
    /* 尚未添加 Remote 的工作区允许没有此目录。 */
  }
  return [...names, ...remotes];
}
export async function adminDependencyReport(root = rootDefault) {
  const result = [];
  for (const directory of await consumers(root)) {
    const manifest = JSON.parse(await readFile(path.join(root, directory, 'package.json'), 'utf8'));
    result.push({
      directory,
      dependency: manifest.dependencies?.['@saas-forge/admin'],
      vue: manifest.dependencies?.vue,
      elementPlus: manifest.dependencies?.['element-plus'],
    });
  }
  return result;
}
export async function findBoundaryViolations(root = rootDefault) {
  const errors = [];
  for (const directory of await consumers(root)) {
    const remote = directory.startsWith('business-remotes/');
    const manifest = JSON.parse(await readFile(path.join(root, directory, 'package.json'), 'utf8'));
    for (const section of [
      'dependencies',
      'devDependencies',
      'peerDependencies',
      'optionalDependencies',
    ])
      for (const name of Object.keys(manifest[section] ?? {})) {
        if (
          /^(?:react(?:-dom|-router|-aria-components)?|antd|@ant-design\/|@saas-forge\/(?:design-system|react-shell))/.test(
            name,
          )
        )
          errors.push(`${directory}: 旧 UI 依赖 ${name}`);
        if (name === '@saas-forge/api-client')
          errors.push(`${directory}: 只能通过 Runtime 调用业务 Client`);
      }
    for (const file of await files(path.join(root, directory, 'src'))) {
      const source = await readFile(file, 'utf8');
      if (!/\.(?:tsx|ts|vue|js|mjs)$/.test(file)) continue;
      const relative = path.relative(root, file);
      if (file.endsWith('.tsx')) errors.push(`${relative}: 旧 UI 文件`);
      if (
        /credentials\s*:|Authorization|X-SF-CSRF|\bCookie\b|new\s+AuthenticationApi|@saas-forge\/api-client|createAuthenticationRuntimeAfterConfig\s*\(/.test(
          source,
        )
      )
        errors.push(`${relative}: 不得实现凭据或第二个 Runtime`);
      if (/\bfetch\s*\(/.test(source)) errors.push(`${relative}: 业务 HTTP 必须通过 Runtime`);
      if (
        remote &&
        /localStorage|sessionStorage|navigator\.languages|useConsole|useLocale|mountConsole|createAuthenticationRuntime/.test(
          source,
        )
      )
        errors.push(`${relative}: Remote 只能消费宿主传入的语言及主题`);
      errors.push(...brandBoundaryViolations(source, relative, { remote }));
    }
  }
  const report = await adminDependencyReport(root);
  for (const entry of report) {
    if (
      entry.dependency !== 'workspace:*' ||
      entry.vue !== '3.5.31' ||
      entry.elementPlus !== '2.13.6'
    )
      errors.push(`${entry.directory}: 必须共享锁定的 admin、Vue 和 Element Plus 版本`);
  }
  return errors;
}
export function brandBoundaryViolations(source, file, options = {}) {
  const script = file.endsWith('.vue')
    ? (source.match(/<script[^>]*>([\s\S]*?)<\/script>/)?.[1] ?? '')
    : source;
  const sourceFile = ts.createSourceFile(
    file,
    script,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX,
  );
  const violations = [];
  const forbiddenImports = new Set([
    'resolveTenantBrandProfile',
    'TenantBrandResolution',
    'TenantBrandProfile',
    'TenantBrandProfileSnapshot',
    'platformBrandProfile',
    'platformBrandTokenSet',
    'useBrandApplication',
    'BrandApplicationContextValue',
    'brandApplicationTitle',
  ]);
  if (options.remote) forbiddenImports.add('useConsole');
  if (options.remote) {
    for (const name of [
      'resolveBrandProfile',
      'platformResolvedBrandProfile',
      'ResolvedBrandProfile',
      'CompleteBrandProfile',
      'BrandResolution',
      'mountConsole',
    ])
      forbiddenImports.add(name);
  }
  const providerAliases = new Set(['ElConfigProvider']);
  const brandVariables = new Set(['brandProfile', 'resolvedBrand', 'platformResolvedBrandProfile']);
  const brandFields = new Set([
    'displayName',
    'logoUrl',
    'faviconUrl',
    'primaryColor',
    'accentColor',
    'profile',
    'tokenSet',
  ]);
  const report = (node, reason) =>
    violations.push(
      `${file}:${sourceFile.getLineAndCharacterOfPosition(node.getStart()).line + 1}: ${reason}`,
    );
  const propertyName = (node) => {
    if (ts.isPropertyAccessExpression(node)) return node.name.text;
    if (ts.isElementAccessExpression(node) && ts.isStringLiteralLike(node.argumentExpression))
      return node.argumentExpression.text;
    return undefined;
  };
  const isBrand = (node) => {
    if (ts.isIdentifier(node)) return brandVariables.has(node.text);
    return (
      ['brandProfile', 'resolvedBrand'].includes(propertyName(node)) ||
      ((ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) &&
        isBrand(node.expression))
    );
  };
  // 先收集别名，避免通过 import alias 或局部变量改名绕过消费检查。
  const collect = (node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteralLike(node.moduleSpecifier)) {
      const specifier = node.moduleSpecifier.text;
      if (
        /shared\/admin\//.test(specifier) ||
        (specifier.startsWith('@saas-forge/admin/') && specifier !== '@saas-forge/admin/styles.css')
      ) {
        report(node, '消费者不得导入内部品牌路径。');
      }
      if (['@saas-forge/admin', '@saas-forge/app-runtime', 'element-plus'].includes(specifier)) {
        const bindings = node.importClause?.namedBindings;
        if (bindings && ts.isNamespaceImport(bindings)) {
          report(node, '品牌边界要求共享包使用可检查的具名导入。');
        }
        if (bindings && ts.isNamedImports(bindings)) {
          for (const element of bindings.elements) {
            const name = (element.propertyName ?? element.name).text;
            if (forbiddenImports.has(name)) report(element, `消费者不得获取品牌入口 ${name}。`);
            if (providerAliases.has(name)) providerAliases.add(element.name.text);
            if (brandVariables.has(name)) brandVariables.add(element.name.text);
          }
        }
      }
    }
    if (ts.isVariableDeclaration(node) && node.initializer && isBrand(node.initializer)) {
      if (ts.isIdentifier(node.name)) brandVariables.add(node.name.text);
      else report(node, '消费者不得解构原始或 Resolved Brand Profile。');
    }
    if (ts.isBindingElement(node)) {
      const name = (node.propertyName ?? node.name).getText(sourceFile).replace(/['"]/g, '');
      if (name === 'brandProfile' || name === 'resolvedBrand') {
        if (options.remote) report(node, 'Remote 不得获取原始或 Resolved Brand Profile。');
        if (ts.isIdentifier(node.name)) brandVariables.add(node.name.text);
        else report(node, '消费者不得解构原始或 Resolved Brand Profile。');
      }
    }
    ts.forEachChild(node, collect);
  };
  collect(sourceFile);
  const visit = (node) => {
    if (ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node)) {
      const name = propertyName(node);
      if (
        (isBrand(node.expression) && brandFields.has(name)) ||
        (options.remote && ['brandProfile', 'resolvedBrand'].includes(name))
      ) {
        report(node, '消费者不得直接读取原始或 Resolved Brand Profile 字段。');
      }
    }
    if (
      (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) &&
      ts.isIdentifier(node.tagName)
    ) {
      if (providerAliases.has(node.tagName.text) && !options.providerEntry) {
        report(node, '消费者不得在入口之外安装第二个 Theme Provider。');
      }
      for (const attribute of node.attributes.properties) {
        if (
          ts.isJsxAttribute(attribute) &&
          /^(?:tenantBrand|onTenantBrandRejected|applicationLogoUrl|applicationLogoAlt|logoUrl|faviconUrl)$/.test(
            attribute.name.text,
          )
        ) {
          report(attribute, '消费者不得使用旧品牌参数或独立渲染品牌素材。');
        }
        if (
          node.tagName.text === 'link' &&
          ts.isJsxAttribute(attribute) &&
          attribute.name.text === 'rel' &&
          attribute.initializer &&
          /icon/.test(attribute.initializer.getText(sourceFile))
        ) {
          report(attribute, '消费者不得独立渲染 favicon。');
        }
      }
    }
    if (ts.isStringLiteralLike(node)) {
      if (/^\/brands\//.test(node.text)) report(node, '消费者不得独立引用品牌素材。');
      if (
        /--(?:el-color-primary|console-(?:primary-foreground|accent(?:-foreground)?))\s*:/.test(
          node.text,
        )
      ) {
        report(node, '消费者不得重写受控 Brand Token。');
      }
      if (
        /^--(?:el-color-primary|console-(?:primary-foreground|accent(?:-foreground)?))$/.test(
          node.text,
        )
      ) {
        const parent = node.parent;
        if (
          ts.isPropertyAssignment(parent) ||
          (ts.isCallExpression(parent) && propertyName(parent.expression) === 'setProperty')
        ) {
          report(node, '消费者不得重写受控 Brand Token。');
        }
      }
    }
    if (
      ts.isBinaryExpression(node) &&
      node.operatorToken.kind === ts.SyntaxKind.EqualsToken &&
      node.left.getText(sourceFile) === 'document.title'
    ) {
      report(node, '消费者不得独立应用品牌标题。');
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);
  if (
    file.endsWith('.vue') &&
    [...providerAliases].some((name) => new RegExp('<' + name + '(?:\\s|/|>)').test(source))
  )
    violations.push(`${file}: 消费者不得安装主题 Provider`);
  if (
    file.endsWith('.vue') &&
    /<ElConfigProvider|<link[^>]+rel=["']icon|["']\/brands\//.test(source)
  )
    violations.push(`${file}: 消费者不得安装主题或引用品牌素材`);
  if (
    /--(?:el-color-primary|console-(?:primary-foreground|accent(?:-foreground)?))\s*:/.test(source)
  )
    violations.push(`${file}: 消费者不得重写品牌 Token`);
  return violations;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const errors = await findBoundaryViolations();
  if (errors.length) throw new Error(errors.join('\n'));
  console.log('Admin consumer boundaries passed');
}
