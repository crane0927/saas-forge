import { describe, expect, it } from 'vitest';

import {
  createTranslator,
  defineMessages,
  isSupportedLocale,
  resolveLocale,
  supportedLocaleRegistry,
} from '../src';

describe('Locale registry and resolution', () => {
  it('keeps the exported Locale union aligned with the build-time registry', () => {
    expect(supportedLocaleRegistry.map(({ locale }) => locale)).toEqual(['zh-CN', 'en-US']);
    expect(isSupportedLocale('zh-CN')).toBe(true);
    expect(isSupportedLocale('zh')).toBe(false);
    expect(isSupportedLocale('fr-FR')).toBe(false);
  });

  it('uses the first exact browser candidate before language-family fallback', () => {
    expect(resolveLocale(['en-GB', 'zh-CN'])).toBe('en-US');
    expect(resolveLocale(['zh-CN', 'en-GB'])).toBe('zh-CN');
  });

  it('falls back from traditional Chinese and regional English by language family', () => {
    expect(resolveLocale(['zh-TW'])).toBe('zh-CN');
    expect(resolveLocale(['en-AU'])).toBe('en-US');
  });

  it('falls back to English for empty, invalid, and unsupported candidates', () => {
    expect(resolveLocale([])).toBe('en-US');
    expect(resolveLocale(['', 'not_a_locale', 'fr-FR'])).toBe('en-US');
  });
});

describe('createTranslator', () => {
  const messages = defineMessages({
    'en-US': {
      greeting: 'Hello, {name}.',
      count: '{count, plural, one {# item} other {# items}}',
      broken: '{name',
    },
    'zh-CN': {
      greeting: '你好，{name}。',
      count: '{count} 项',
      broken: '{name',
    },
  });

  it('formats typed messages and uses the active Locale', () => {
    const translator = createTranslator({
      namespace: '@saas-forge/example',
      locale: 'zh-CN',
      messages,
    });

    expect(translator.namespace).toBe('@saas-forge/example');
    expect(translator.translate('greeting', { name: 'Ada' })).toBe('你好，Ada。');
    expect(translator.translate('count', { count: 2 })).toBe('2 项');
  });

  it('uses the same English key when the active-language resource is absent', () => {
    const translator = createTranslator({
      namespace: '@saas-forge/example',
      locale: 'zh-CN',
      messages: {
        'en-US': { greeting: 'Hello, {name}.' },
        'zh-CN': {},
      },
    });

    expect(translator.translate('greeting', { name: 'Ada' })).toBe('Hello, Ada.');
  });

  it('returns a safe recovery message when both resources cannot be formatted', () => {
    const translator = createTranslator({
      namespace: '@saas-forge/example',
      locale: 'en-US',
      messages,
    });

    expect(translator.translate('broken', { name: 'Ada' })).toBe(
      'This content is temporarily unavailable.',
    );
  });
});
