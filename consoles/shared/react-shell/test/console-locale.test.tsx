import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  ConsoleLocaleProvider,
  ConsoleLocaleSelector,
  consoleLocalePreferenceKey,
  resolveInitialConsoleLocale,
  useConsoleLocale,
} from '../src';

const nativeStorage = window.localStorage;
const localStorageDescriptor = Object.getOwnPropertyDescriptor(window, 'localStorage');
const navigatorLanguagesDescriptor = Object.getOwnPropertyDescriptor(navigator, 'languages');

globalThis.ResizeObserver = class ResizeObserverMock {
  public constructor(callback: ResizeObserverCallback) {
    void callback;
  }

  public disconnect(): void {}

  public observe(): void {}

  public unobserve(): void {}
};

afterEach(() => {
  cleanup();
  restoreLocalStorage();
  restoreNavigatorLanguages();
  nativeStorage.clear();
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

  it('exposes enabled locales and writes only an explicitly selected exact Locale', () => {
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <LocaleProbe />
        <LocaleControls />
      </ConsoleLocaleProvider>,
    );

    expect(screen.getByTestId('enabled-locales').textContent).toBe('zh-CN,en-US');
    fireEvent.click(screen.getByRole('button', { name: '切换为简体中文' }));

    expect(screen.getByTestId('current-locale').textContent).toBe('zh-CN');
    expect(document.documentElement.lang).toBe('zh-CN');
    expect(nativeStorage.getItem(consoleLocalePreferenceKey)).toBe('zh-CN');
  });

  it('uses the shared controlled selector and preserves sibling input state while switching', async () => {
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <ConsoleLocaleSelector />
        <InputProbe />
        <LocaleProbe />
      </ConsoleLocaleProvider>,
    );

    const input = screen.getByLabelText('未提交内容');
    fireEvent.change(input, { target: { value: 'keep-me' } });
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Language / 语言' }));
    fireEvent.click(await screen.findByText('简体中文'));

    expect(screen.getByTestId('current-locale').textContent).toBe('zh-CN');
    expect((input as HTMLInputElement).value).toBe('keep-me');
    expect(nativeStorage.getItem(consoleLocalePreferenceKey)).toBe('zh-CN');
  });

  it('re-reads the current storage value on storage events and visible-tab activation', async () => {
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <LocaleProbe />
      </ConsoleLocaleProvider>,
    );

    nativeStorage.setItem(consoleLocalePreferenceKey, 'zh-CN');
    fireEvent(window, new StorageEvent('storage', { key: consoleLocalePreferenceKey }));
    await expectLocale('zh-CN');

    nativeStorage.removeItem(consoleLocalePreferenceKey);
    fireEvent(document, new Event('visibilitychange'));
    await expectLocale('en-US');
  });

  it('updates from browser language changes only without a manual preference', async () => {
    setNavigatorLanguages(['zh-TW']);
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <LocaleProbe />
        <LocaleControls />
      </ConsoleLocaleProvider>,
    );

    fireEvent(window, new Event('languagechange'));
    await expectLocale('zh-CN');

    fireEvent.click(screen.getByRole('button', { name: '切换为英文' }));
    setNavigatorLanguages(['zh-CN']);
    fireEvent(window, new Event('languagechange'));

    expect(screen.getByText('en-US')).toBeTruthy();
  });

  it('keeps a local selection when storage writes fail until a later readable value arrives', async () => {
    const blockedStorage = new BlockedStorage();
    installLocalStorage(blockedStorage);
    render(
      <ConsoleLocaleProvider initialLocale="en-US">
        <LocaleProbe />
        <LocaleControls />
      </ConsoleLocaleProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: '切换为简体中文' }));
    expect(screen.getByText('zh-CN')).toBeTruthy();
    expect(blockedStorage.writes).toBe(1);

    setNavigatorLanguages(['en-US']);
    fireEvent(window, new Event('languagechange'));
    expect(screen.getByText('zh-CN')).toBeTruthy();

    installLocalStorage(nativeStorage);
    nativeStorage.setItem(consoleLocalePreferenceKey, 'en-US');
    fireEvent(window, new StorageEvent('storage', { key: consoleLocalePreferenceKey }));
    await expectLocale('en-US');
  });
});

function LocaleProbe() {
  const { enabledLocales, locale } = useConsoleLocale();
  return (
    <>
      <p data-testid="current-locale">{locale}</p>
      <p data-testid="enabled-locales">{enabledLocales.join(',')}</p>
    </>
  );
}

function LocaleControls() {
  const { setLocale } = useConsoleLocale();
  return (
    <>
      <button
        type="button"
        onClick={() => {
          setLocale('zh-CN');
        }}
      >
        切换为简体中文
      </button>
      <button
        type="button"
        onClick={() => {
          setLocale('en-US');
        }}
      >
        切换为英文
      </button>
    </>
  );
}

function InputProbe() {
  return <input aria-label="未提交内容" defaultValue="" />;
}

async function expectLocale(locale: string): Promise<void> {
  await waitFor(() => {
    expect(screen.getByTestId('current-locale').textContent).toBe(locale);
    expect(document.documentElement.lang).toBe(locale);
  });
}

function installLocalStorage(storage: Storage): void {
  Object.defineProperty(window, 'localStorage', { configurable: true, value: storage });
}

function restoreLocalStorage(): void {
  if (localStorageDescriptor === undefined) {
    Reflect.deleteProperty(window, 'localStorage');
    return;
  }
  Object.defineProperty(window, 'localStorage', localStorageDescriptor);
}

function setNavigatorLanguages(languages: readonly string[]): void {
  Object.defineProperty(navigator, 'languages', { configurable: true, value: languages });
}

function restoreNavigatorLanguages(): void {
  if (navigatorLanguagesDescriptor === undefined) return;
  Object.defineProperty(navigator, 'languages', navigatorLanguagesDescriptor);
}

class BlockedStorage implements Storage {
  public get length(): number {
    return 0;
  }

  public writes = 0;

  public clear(): void {
    throw new Error('storage is blocked');
  }

  public getItem(): string | null {
    throw new Error('storage is blocked');
  }

  public key(): string | null {
    throw new Error('storage is blocked');
  }

  public removeItem(): void {
    throw new Error('storage is blocked');
  }

  public setItem(): void {
    this.writes += 1;
    throw new Error('storage is blocked');
  }
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
