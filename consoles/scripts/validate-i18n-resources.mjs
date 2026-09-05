import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { parse, TYPE } from '@formatjs/icu-messageformat-parser';

const consoleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const resourceDirectories = [
  'platform-console/src/messages',
  'shared/design-system/src/messages',
  'shared/react-shell/src/messages',
  'tenant-console-shell/src/messages',
];
const enabledLocales = ['en-US', 'zh-CN'];

export async function validateI18nResources(root = consoleRoot) {
  const errors = [];
  for (const relativeDirectory of resourceDirectories) {
    errors.push(...(await validateResourceDirectory(path.join(root, relativeDirectory))));
  }
  return errors;
}

export async function validateResourceDirectory(directory) {
  const errors = [];
  const resources = new Map();

  for (const locale of enabledLocales) {
    const file = path.join(directory, `${locale}.json`);
    try {
      resources.set(locale, await readResource(file, errors));
    } catch (error) {
      errors.push(`${file}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  const declaredFiles = new Set(await readdir(directory));
  for (const locale of enabledLocales) {
    declaredFiles.delete(`${locale}.json`);
  }
  for (const file of declaredFiles) {
    if (file.endsWith('.json')) {
      errors.push(`${path.join(directory, file)}: only enabled Locale resources are allowed.`);
    }
  }

  const english = resources.get('en-US');
  if (english === undefined) return errors;
  for (const locale of enabledLocales.filter((candidate) => candidate !== 'en-US')) {
    const resource = resources.get(locale);
    if (resource === undefined) continue;
    compareKeysAndParameters(english, resource, locale, directory, errors);
  }
  return errors;
}

async function readResource(file, errors) {
  const parsed = JSON.parse(await readFile(file, 'utf8'));
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
