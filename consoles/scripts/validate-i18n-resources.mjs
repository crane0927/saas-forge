import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { parse, TYPE } from '@formatjs/icu-messageformat-parser';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ignoredDirectoryNames = new Set([
  '.generated',
  '.git',
  'browser-test',
  'dist',
  'integration-test',
  'node_modules',
  'test',
]);
const localeRegistryFile = path.join(consoleRoot, 'shared/i18n/src/locale-registry.json');
const localeRegistry = JSON.parse(await readFile(localeRegistryFile, 'utf8'));
export const enabledLocales = Object.freeze(Object.keys(localeRegistry));

// 同一个 `src` 下的 `locales/` 与 `messages/` 是两套并行资源：前者由模板 shell 的
// vue-i18n 机制解析，后者由自建 createTranslator 解析。键出现交集就表示同一文案由两套
// 实现同时负责，一处改动另一处不跟着变也无人发现。除下面已登记的一处，任何新增的跨目录
// 重复键都必须显式评审；原登记项消失（例如 Issue #199 完成收敛后）同样必须显式评审并
// 更新此表，避免登记本身腐烂成无人知晓的豁免。
const reviewedCrossDirectoryOverlaps = new Map([
  [
    'platform-console/src',
    new Set(['tenantsTitle', 'planDefinitionsTitle', 'quotaDefinitionsTitle']),
  ],
]);

export async function validateI18nResources(root = consoleRoot, { locales = enabledLocales } = {}) {
  const errors = [];
  const resourceDirectories = await discoverResourceDirectories(root);
  if (resourceDirectories.length === 0) {
    return [`${root}: no Console i18n resource directories were discovered.`];
  }
  for (const directory of resourceDirectories) {
    errors.push(...(await validateResourceDirectory(directory, { locales })));
  }
  errors.push(...(await validateCrossDirectoryOverlaps(root, resourceDirectories)));
  return errors;
}

export async function findCrossDirectoryOverlaps(root, resourceDirectories) {
  const siblingsByParent = new Map();
  for (const directory of resourceDirectories) {
    const parent = path.dirname(directory);
    siblingsByParent.set(parent, [...(siblingsByParent.get(parent) ?? []), directory]);
  }

  const keysByDirectory = new Map();
  for (const directory of resourceDirectories) {
    try {
      const parsed = JSON.parse(await readFile(path.join(directory, 'en-US.json'), 'utf8'));
      keysByDirectory.set(directory, new Set(Object.keys(parsed)));
    } catch {
      // 读取或解析失败已由目录级校验报告，这里不重复报错。
      keysByDirectory.set(directory, undefined);
    }
  }

  const overlapsByParent = new Map();
  for (const [parent, siblings] of siblingsByParent) {
    const overlaps = new Set();
    for (let left = 0; left < siblings.length; left += 1) {
      for (let right = left + 1; right < siblings.length; right += 1) {
        const leftKeys = keysByDirectory.get(siblings[left]);
        const rightKeys = keysByDirectory.get(siblings[right]);
        if (leftKeys === undefined || rightKeys === undefined) continue;
        for (const key of leftKeys) {
          if (rightKeys.has(key)) overlaps.add(key);
        }
      }
    }
    const relativeParent = path.relative(root, parent).split(path.sep).join('/');
    overlapsByParent.set(relativeParent, overlaps);
  }
  return overlapsByParent;
}

async function validateCrossDirectoryOverlaps(root, resourceDirectories) {
  const errors = [];
  const overlapsByParent = await findCrossDirectoryOverlaps(root, resourceDirectories);

  // 只断言本次实际发现的父目录：临时装置或独立模块根不含 platform-console/src 时，
  // 不能因为该登记项"消失"而失败。反之，只要该父目录仍在（哪怕只剩 locales/ 一侧），
  // 登记项就必须仍然成立，这样收敛完成后登记会立刻要求同步，不会腐烂成豁免。
  for (const parent of [...overlapsByParent.keys()].sort()) {
    const actual = overlapsByParent.get(parent) ?? new Set();
    const expected = reviewedCrossDirectoryOverlaps.get(parent) ?? new Set();
    const added = [...actual].filter((key) => !expected.has(key)).sort();
    const removed = [...expected].filter((key) => !actual.has(key)).sort();
    if (added.length === 0 && removed.length === 0) continue;

    const details = [
      added.length > 0 ? `出现未登记的重复键 ${added.join('、')}` : '',
      removed.length > 0 ? `已登记的重复键 ${removed.join('、')} 已消失` : '',
    ]
      .filter(Boolean)
      .join('；');
    errors.push(
      `${parent}: 跨目录重复键集合发生变化（${details}）；同一文案不得由 locales/ 与 messages/ 两套机制同时负责，` +
        '变化必须显式评审并更新 scripts/validate-i18n-resources.mjs 中的登记表。',
    );
  }
  return errors;
}

export async function discoverResourceDirectories(root = consoleRoot) {
  const directories = [];

  async function visit(directory, insideResourceTree = false) {
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch {
      return;
    }

    const hasJsonResource = entries.some((entry) => entry.isFile() && entry.name.endsWith('.json'));
    if (insideResourceTree && hasJsonResource) {
      directories.push(directory);
    }

    for (const entry of entries) {
      if (!entry.isDirectory() || ignoredDirectoryNames.has(entry.name)) continue;
      const child = path.join(directory, entry.name);
      const childIsResourceTree =
        insideResourceTree ||
        ((entry.name === 'messages' || entry.name === 'locales') &&
          path.basename(directory) === 'src');
      await visit(child, childIsResourceTree);
    }
  }

  await visit(root);
  return directories.sort();
}

export async function validateResourceDirectory(directory, { locales = enabledLocales } = {}) {
  const errors = [];
  const resources = new Map();

  for (const locale of locales) {
    const file = path.join(directory, `${locale}.json`);
    try {
      resources.set(locale, await readResource(file, errors));
    } catch (error) {
      errors.push(`${file}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  let declaredFiles;
  try {
    declaredFiles = new Set(await readdir(directory));
  } catch (error) {
    errors.push(`${directory}: ${error instanceof Error ? error.message : String(error)}`);
    return errors;
  }
  for (const locale of locales) {
    declaredFiles.delete(`${locale}.json`);
  }
  for (const file of declaredFiles) {
    if (file.endsWith('.json')) {
      errors.push(`${path.join(directory, file)}: only enabled Locale resources are allowed.`);
    }
  }

  const english = resources.get('en-US');
  if (english === undefined) return errors;
  for (const locale of locales.filter((candidate) => candidate !== 'en-US')) {
    const resource = resources.get(locale);
    if (resource === undefined) continue;
    compareKeysAndParameters(english, resource, locale, directory, errors);
  }
  return errors;
}

async function readResource(file, errors) {
  const source = await readFile(file, 'utf8');
  for (const key of findDuplicateTopLevelKeys(source)) {
    errors.push(`${file}:${key}: duplicate key is not allowed.`);
  }

  const parsed = JSON.parse(source);
  if (!isFlatMessageCatalog(parsed)) {
    throw new Error('resource must be a flat JSON object with string messages.');
  }

  const messages = new Map();
  for (const [key, message] of Object.entries(parsed)) {
    if (message.trim() === '') {
      errors.push(`${file}:${key}: message must not be blank.`);
      continue;
    }
    try {
      messages.set(key, parameterSignature(parse(message, { captureLocation: false })));
    } catch (error) {
      errors.push(
        `${file}:${key}: invalid ICU message (${error instanceof Error ? error.message : String(error)}).`,
      );
    }
  }
  return { keys: new Set(Object.keys(parsed)), parameters: messages };
}

function findDuplicateTopLevelKeys(source) {
  const duplicates = new Set();
  const keys = new Set();
  let index = skipWhitespace(source, 0);
  if (source[index] !== '{') return duplicates;
  index += 1;

  while (index < source.length) {
    index = skipWhitespace(source, index);
    if (source[index] === '}') return duplicates;
    if (source[index] !== '"') return duplicates;

    const keyEnd = findJsonStringEnd(source, index);
    if (keyEnd === undefined) return duplicates;
    let key;
    try {
      key = JSON.parse(source.slice(index, keyEnd + 1));
    } catch {
      return duplicates;
    }
    if (keys.has(key)) duplicates.add(key);
    keys.add(key);

    index = skipWhitespace(source, keyEnd + 1);
    if (source[index] !== ':') return duplicates;
    index = findTopLevelValueEnd(source, index + 1);
    if (source[index] === ',') {
      index += 1;
      continue;
    }
    return duplicates;
  }
  return duplicates;
}

function skipWhitespace(source, start) {
  let index = start;
  while (/\s/u.test(source[index] ?? '')) index += 1;
  return index;
}

function findJsonStringEnd(source, start) {
  let escaped = false;
  for (let index = start + 1; index < source.length; index += 1) {
    if (escaped) {
      escaped = false;
    } else if (source[index] === '\\') {
      escaped = true;
    } else if (source[index] === '"') {
      return index;
    }
  }
  return undefined;
}

function findTopLevelValueEnd(source, start) {
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') {
      inString = true;
    } else if (character === '{' || character === '[') {
      depth += 1;
    } else if (character === '}' || character === ']') {
      if (depth === 0) return index;
      depth -= 1;
    } else if (character === ',' && depth === 0) {
      return index;
    }
  }
  return source.length;
}

function isFlatMessageCatalog(value) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Object.values(value).every((message) => typeof message === 'string')
  );
}

function parameterSignature(elements) {
  const parameters = new Map();
  const visitOptions = (options) => {
    for (const option of Object.values(options)) {
      visit(option.value);
    }
  };
  const visit = (nodes) => {
    for (const element of nodes) {
      switch (element.type) {
        case TYPE.argument:
          registerParameter(parameters, element.value, 'argument');
          break;
        case TYPE.number:
          registerParameter(parameters, element.value, 'number');
          break;
        case TYPE.date:
          registerParameter(parameters, element.value, 'date');
          break;
        case TYPE.time:
          registerParameter(parameters, element.value, 'time');
          break;
        case TYPE.select:
          registerParameter(parameters, element.value, 'select');
          visitOptions(element.options);
          break;
        case TYPE.plural:
          registerParameter(parameters, element.value, 'plural');
          visitOptions(element.options);
          break;
        case TYPE.tag:
          throw new Error('rich-text tags are not supported in first-version messages.');
        default:
          break;
      }
    }
  };
  visit(elements);
  return parameters;
}

function registerParameter(parameters, name, type) {
  const existing = parameters.get(name);
  if (existing !== undefined && existing !== type) {
    throw new Error(`parameter ${name} uses incompatible ICU types.`);
  }
  parameters.set(name, type);
}

function compareKeysAndParameters(english, resource, locale, directory, errors) {
  for (const key of english.keys) {
    if (!resource.keys.has(key)) {
      errors.push(`${directory}/${locale}.json: missing key ${key}.`);
    }
  }
  for (const key of resource.keys) {
    if (!english.keys.has(key)) {
      errors.push(`${directory}/${locale}.json: extra key ${key}.`);
      continue;
    }
    const englishParameters = english.parameters.get(key);
    const localizedParameters = resource.parameters.get(key);
    if (!sameParameters(englishParameters, localizedParameters)) {
      errors.push(`${directory}/${locale}.json:${key}: ICU parameters must match en-US.`);
    }
  }
}

function sameParameters(left, right) {
  if (left === undefined || right === undefined || left.size !== right.size) return false;
  return [...left].every(([name, type]) => right.get(name) === type);
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const errors = await validateI18nResources();
  if (errors.length > 0) {
    console.error(errors.join('\n'));
    process.exitCode = 1;
  } else {
    console.log('i18n resources are valid.');
  }
}
