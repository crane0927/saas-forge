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

export async function validateI18nResources(root = consoleRoot, { locales = enabledLocales } = {}) {
  const errors = [];
  const resourceDirectories = await discoverResourceDirectories(root);
  if (resourceDirectories.length === 0) {
    return [`${root}: no Console i18n resource directories were discovered.`];
  }
  for (const directory of resourceDirectories) {
    errors.push(...(await validateResourceDirectory(directory, { locales })));
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
