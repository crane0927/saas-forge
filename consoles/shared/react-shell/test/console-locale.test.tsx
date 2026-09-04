import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  ConsoleLocaleProvider,
  consoleLocalePreferenceKey,
  resolveInitialConsoleLocale,
  useConsoleLocale,
} from '../src';

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  document.documentElement.lang = '';
});

describe('Console Locale initialization', () => {
  it('prefers an exact supported manual preference without writing it back', () => {
    const storage = new MapStorage(new Map([[consoleLocalePreferenceKey, 'zh-CN']]));

    expect(resolveInitialConsoleLocale({ storage, browserLanguages: ['en-GB'] })).toBe('zh-CN');
    expect(storage.writes).toBe(0);
  });

  it('ignores invalid stored preferences and falls back to browser candidates', () => {
    const storage = new MapStorage(new Map([[consoleLocalePreferenceKey, 'fr-FR']]));

    expect(resolveInitialConsoleLocale({ storage, browserLanguages: ['zh-TW'] })).toBe('zh-CN');
  });

  it('continues with browser matching when preference storage cannot be read', () => {
    expect(
      resolveInitialConsoleLocale({
        storage: {
          getItem() {
            throw new Error('storage is blocked');
          },
        },
        browserLanguages: ['en-GB'],
      }),
    ).toBe('en-US');
  });

  it('keeps the document language synchronized with the Locale Context', () => {
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <LocaleProbe />
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByText('en-US')).toBeTruthy();
    expect(document.documentElement.lang).toBe('en-US');
  });
});

function LocaleProbe() {
  return <p>{useConsoleLocale().locale}</p>;
}

class MapStorage {
  public writes = 0;

  public constructor(private readonly values: Map<string, string>) {}

  public getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  public setItem(key: string, value: string): void {
    this.writes += 1;
    this.values.set(key, value);
  }
}
