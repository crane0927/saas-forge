import { describe, expect, it } from 'vitest';

import {
  createTranslator,
  defineMessages,
  formatDate,
  formatInstant,
  formatMoney,
  formatNumber,
  isSupportedLocale,
  resolveLocale,
  supportedLocaleRegistry,
} from '../src';

describe('locale-aware formatting', () => {
  it('formats calendar dates without interpreting them in a local time zone', () => {
    expect(formatDate({ value: '2026-01-02', locale: 'en-US' })).toBe('Jan 2, 2026');
    expect(formatDate({ value: '2026-01-02', locale: 'zh-CN' })).toBe('2026年1月2日');
    expect(formatDate({ value: '2024-02-29', locale: 'en-US' })).toBe('Feb 29, 2024');
  });

  it('formats the same instant in the browser zone or an explicit valid zone', () => {
    const value = '2026-01-02T01:30:00Z';
    expect(formatInstant({ value, locale: 'en-US', timeZone: 'America/Los_Angeles' })).toContain(
      'Jan 1, 2026',
    );
    expect(formatInstant({ value, locale: 'zh-CN', timeZone: 'Asia/Shanghai' })).toContain(
      '2026年1月2日',
    );
    expect(formatInstant({ value, locale: 'en-US' })).not.toBe(
      'This content is temporarily unavailable.',
    );
  });

  it('preserves large integers, decimal precision, trailing zeros, and negative zero', () => {
    expect(formatNumber({ value: '123456789012345678901234567890.0012300', locale: 'en-US' })).toBe(
      '123,456,789,012,345,678,901,234,567,890.0012300',
    );
    expect(formatNumber({ value: '-0.00', locale: 'zh-CN' })).toBe('-0.00');
  });

  it('requires an explicit currency while preserving the supplied decimal precision', () => {
    expect(
      formatMoney({ value: '12345678901234567890.40', currency: 'USD', locale: 'en-US' }),
    ).toBe('$12,345,678,901,234,567,890.40');
    expect(formatMoney({ value: '1200', currency: 'JPY', locale: 'zh-CN' })).toBe('JP¥1,200');
    expect(formatMoney({ value: '1200.500', currency: 'EUR', locale: 'en-US' })).toBe('€1,200.500');
  });

  it('uses localized safe recovery for invalid dates, zones, decimals, and currencies', () => {
    expect(formatDate({ value: '2026-02-30', locale: 'zh-CN' })).toBe('暂时无法显示此内容。');
    expect(
      formatInstant({ value: '2026-01-02T01:30:00Z', locale: 'en-US', timeZone: 'Mars/Olympus' }),
    ).toBe('This content is temporarily unavailable.');
    expect(formatNumber({ value: '1e3', locale: 'en-US' })).toBe(
      'This content is temporarily unavailable.',
    );
    expect(formatNumber({ value: '12\n', locale: 'en-US' })).toBe(
      'This content is temporarily unavailable.',
    );
    expect(formatMoney({ value: '12.00', currency: '', locale: 'zh-CN' })).toBe(
      '暂时无法显示此内容。',
    );
  });

  it('allows a lossless formatted result to be inserted as a text message parameter', () => {
    const translator = createTranslator({
      namespace: '@saas-forge/format-example',
      locale: 'en-US',
      messages: defineMessages({
        'en-US': { balance: 'Balance: {value}' },
        'zh-CN': { balance: '余额：{value}' },
      }),
    });

    expect(
      translator.translate('balance', {
        value: formatMoney({
          value: '12345678901234567890.400',
          currency: 'USD',
          locale: 'en-US',
        }),
      }),
    ).toBe('Balance: $12,345,678,901,234,567,890.400');
  });
});

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
