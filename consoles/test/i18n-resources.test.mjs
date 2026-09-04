import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  validateI18nResources,
  validateResourceDirectory,
} from '../scripts/validate-i18n-resources.mjs';

test('validates the enabled Console resources', async () => {
  assert.deepEqual(await validateI18nResources(), []);
});

test('rejects invalid ICU and mismatched parameters in a resource fixture', async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'saas-forge-i18n-resource-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(
    path.join(directory, 'en-US.json'),
    JSON.stringify({
      greeting: 'Hello, {name}.',
      count: '{count, plural, one {# item} other {# items}}',
    }),
  );
  await writeFile(
    path.join(directory, 'zh-CN.json'),
    JSON.stringify({ greeting: '你好，{user}。', count: '{count' }),
  );

  const errors = await validateResourceDirectory(directory);

  assert.ok(errors.some((error) => error.includes('greeting') && error.includes('parameters')));
  assert.ok(errors.some((error) => error.includes('count') && error.includes('invalid ICU')));
});

test('accepts matching plural parameters in both enabled Locale resources', async (context) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'saas-forge-i18n-plural-'));
  context.after(() => rm(directory, { recursive: true, force: true }));
  await writeFile(
    path.join(directory, 'en-US.json'),
    JSON.stringify({ count: '{count, plural, one {# item} other {# items}}' }),
  );
  await writeFile(
    path.join(directory, 'zh-CN.json'),
    JSON.stringify({ count: '{count, plural, one {# 项} other {# 项}}' }),
  );

  assert.deepEqual(await validateResourceDirectory(directory), []);
});

test('rejects an incomplete enabled-Locale resource fixture', async (context) => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'saas-forge-i18n-workspace-'));
  context.after(() => rm(root, { recursive: true, force: true }));
  const designSystemDirectory = path.join(root, 'shared/design-system/src/messages');
  await mkdir(designSystemDirectory, { recursive: true });
  await writeFile(
    path.join(designSystemDirectory, 'en-US.json'),
    JSON.stringify({ greeting: 'Hello.' }),
  );
  for (const relativeDirectory of [
    'platform-console/src/messages',
    'shared/react-shell/src/messages',
  ]) {
    const directory = path.join(root, relativeDirectory);
    await mkdir(directory, { recursive: true });
    await writeFile(path.join(directory, 'en-US.json'), JSON.stringify({ greeting: 'Hello.' }));
    await writeFile(path.join(directory, 'zh-CN.json'), JSON.stringify({ greeting: '你好。' }));
  }

  const errors = await validateI18nResources(root);

  assert.ok(errors.some((error) => error.includes('zh-CN.json')));
});
