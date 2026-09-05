import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  discoverResourceDirectories,
  enabledLocales,
  validateI18nResources,
  validateResourceDirectory,
} from '../scripts/validate-i18n-resources.mjs';

const productionLocales = ['zh-CN', 'en-US'];

test('derives production enabled Locales from the shared registry', () => {
  assert.deepEqual(enabledLocales, productionLocales);
});

test('automatically discovers and validates the current Console resources', async () => {
  const directories = await discoverResourceDirectories();

  assert.ok(
    directories.some((directory) =>
      directory.endsWith('business-remotes/design-system-consumer-fixture/src/locales'),
    ),
  );
  assert.ok(directories.some((directory) => directory.endsWith('platform-console/src/messages')));
  assert.ok(
    directories.some((directory) => directory.endsWith('shared/design-system/src/messages/forms')),
  );
  assert.ok(directories.some((directory) => directory.endsWith('shared/react-shell/src/messages')));
  assert.ok(
    directories.some((directory) => directory.endsWith('tenant-console-shell/src/messages')),
  );
  assert.deepEqual(await validateI18nResources(), []);
});

test('discovers a new module and validates a temporary third Locale without production enablement', async (context) => {
  const root = await temporaryDirectory(context, 'saas-forge-i18n-workspace-');
  const moduleDirectory = path.join(root, 'new-console/src/messages');
  await writeCatalogs(moduleDirectory, {
    'en-US': { greeting: 'Hello, {name}.' },
    'zh-CN': { greeting: '你好，{name}。' },
    'zh-TW': { greeting: '您好，{name}。' },
  });

  assert.deepEqual(await discoverResourceDirectories(root), [moduleDirectory]);
  assert.deepEqual(await validateI18nResources(root, { locales: ['en-US', 'zh-CN', 'zh-TW'] }), []);
  assert.deepEqual(enabledLocales, productionLocales);
});

test('rejects a newly discovered module that omits an enabled Locale resource', async (context) => {
  const root = await temporaryDirectory(context, 'saas-forge-i18n-missing-module-');
  const moduleDirectory = path.join(root, 'official-remote/src/locales');
  await writeCatalogs(moduleDirectory, {
    'en-US': { greeting: 'Hello.' },
    'zh-CN': { greeting: '你好。' },
  });

  const errors = await validateI18nResources(root, {
    locales: ['en-US', 'zh-CN', 'zh-TW'],
  });

  assert.ok(errors.some((error) => error.includes('zh-TW.json') && error.includes('ENOENT')));
});

const invalidResourceFixtures = [
  {
    name: 'missing key',
    english: { greeting: 'Hello.', farewell: 'Goodbye.' },
    localized: { greeting: '你好。' },
    expected: 'missing key farewell',
  },
  {
    name: 'extra key',
    english: { greeting: 'Hello.' },
    localized: { greeting: '你好。', farewell: '再见。' },
    expected: 'extra key farewell',
  },
  {
    name: 'non-string value',
    english: { greeting: 'Hello.' },
    localized: { greeting: 1 },
    expected: 'flat JSON object with string messages',
  },
  {
    name: 'blank message',
    english: { greeting: 'Hello.' },
    localized: { greeting: '   ' },
    expected: 'message must not be blank',
  },
  {
    name: 'invalid ICU',
    english: { greeting: 'Hello, {name}.' },
    localized: { greeting: '你好，{name' },
    expected: 'invalid ICU message',
  },
  {
    name: 'different parameter name inside a plural branch',
    english: { count: '{count, plural, one {{owner} has one item} other {{owner} has # items}}' },
    localized: { count: '{count, plural, other {{user} 有 # 项}}' },
    expected: 'ICU parameters must match en-US',
  },
  {
    name: 'incompatible parameter type',
    english: { count: '{count, number}' },
    localized: { count: '{count}' },
    expected: 'ICU parameters must match en-US',
  },
  {
    name: 'missing required other branch',
    english: { count: '{count, plural, one {one item} other {# items}}' },
    localized: { count: '{count, plural, one {一项}}' },
    expected: 'invalid ICU message',
  },
  {
    name: 'rich-text tag',
    english: { greeting: 'Hello, {name}.' },
    localized: { greeting: '<strong>{name}</strong>' },
    expected: 'rich-text tags are not supported',
  },
];

for (const fixture of invalidResourceFixtures) {
  test(`rejects ${fixture.name} in an independent resource fixture`, async (context) => {
    const directory = await temporaryDirectory(context, 'saas-forge-i18n-invalid-');
    await writeCatalogs(directory, {
      'en-US': fixture.english,
      'zh-CN': fixture.localized,
    });

    const errors = await validateResourceDirectory(directory);

    assert.ok(
      errors.some((error) => error.includes(fixture.expected)),
      `Expected ${JSON.stringify(errors)} to include ${fixture.expected}`,
    );
  });
}

test('rejects duplicate top-level keys even though JSON.parse would overwrite them', async (context) => {
  const directory = await temporaryDirectory(context, 'saas-forge-i18n-duplicate-');
  await mkdir(directory, { recursive: true });
  await writeFile(path.join(directory, 'en-US.json'), '{"greeting":"Hello."}');
  await writeFile(path.join(directory, 'zh-CN.json'), '{"greeting":"你好。","greeting":"您好。"}');

  const errors = await validateResourceDirectory(directory);

  assert.ok(errors.some((error) => error.includes('greeting: duplicate key')));
});

test('allows each Locale to use its own plural categories', async (context) => {
  const directory = await temporaryDirectory(context, 'saas-forge-i18n-plural-');
  await writeCatalogs(directory, {
    'en-US': { count: '{count, plural, one {# item} other {# items}}' },
    'zh-CN': { count: '{count, plural, other {# 项}}' },
  });

  assert.deepEqual(await validateResourceDirectory(directory), []);
});

test('rejects resource files for a Locale that is not enabled', async (context) => {
  const directory = await temporaryDirectory(context, 'saas-forge-i18n-disabled-');
  await writeCatalogs(directory, {
    'en-US': { greeting: 'Hello.' },
    'zh-CN': { greeting: '你好。' },
    'zh-TW': { greeting: '您好。' },
  });

  const errors = await validateResourceDirectory(directory);

  assert.ok(errors.some((error) => error.includes('zh-TW.json: only enabled Locale')));
});

async function temporaryDirectory(context, prefix) {
  const directory = await mkdtemp(path.join(os.tmpdir(), prefix));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

async function writeCatalogs(directory, catalogs) {
  await mkdir(directory, { recursive: true });
  await Promise.all(
    Object.entries(catalogs).map(([locale, catalog]) =>
      writeFile(path.join(directory, `${locale}.json`), JSON.stringify(catalog)),
    ),
  );
}
