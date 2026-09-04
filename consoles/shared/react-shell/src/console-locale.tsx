import {
  isSupportedLocale,
  resolveLocale,
  supportedLocaleRegistry,
  supportedLocales,
  type SupportedLocale,
} from '@saas-forge/i18n';
import { SelectField } from '@saas-forge/design-system';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export const consoleLocalePreferenceKey = 'sf:ui:locale';

export interface ConsoleLocaleProviderProps {
  readonly children: ReactNode;
  readonly initialLocale?: SupportedLocale;
}

export interface ConsoleLocaleContextValue {
  readonly locale: SupportedLocale;
  readonly enabledLocales: readonly SupportedLocale[];
  readonly setLocale: (locale: SupportedLocale) => void;
}

interface LocalePreferenceStorage {
  getItem(key: string): string | null;
}

interface LocalePreferenceWriteStorage extends LocalePreferenceStorage {
  setItem(key: string, value: string): void;
}

type LocalePreferenceState =
  | { readonly kind: 'automatic' }
  | { readonly kind: 'manual' }
  | { readonly kind: 'local-selection' }
  | { readonly kind: 'unavailable' };

interface InitialConsoleLocale {
  readonly locale: SupportedLocale;
  readonly preferenceState: LocalePreferenceState;
}

const ConsoleLocaleContext = createContext<ConsoleLocaleContextValue | undefined>(undefined);
const localeOptions = supportedLocaleRegistry.map(({ locale, selfName }) => ({
  value: locale,
  label: selfName,
}));

/**
 * 启动时只读取精确合法的手动偏好；自动匹配结果不在这里写入存储。
 */
export function resolveInitialConsoleLocale({
  storage = browserStorage(),
  browserLanguages = getBrowserLanguages(),
}: {
  readonly storage?: LocalePreferenceStorage;
  readonly browserLanguages?: readonly unknown[];
} = {}): SupportedLocale {
  return resolveInitialConsoleLocaleState({ storage, browserLanguages }).locale;
}

export function ConsoleLocaleProvider({ children, initialLocale }: ConsoleLocaleProviderProps) {
  const [initial] = useState(resolveInitialConsoleLocaleState);
  const [locale, setCurrentLocale] = useState(() => initialLocale ?? initial.locale);
  const [preferenceState, setPreferenceState] = useState<LocalePreferenceState>(
    () => initial.preferenceState,
  );

  const refreshLocaleFromStorage = useCallback(() => {
    const preference = readLocalePreference(browserStorage());
    if (preference.kind === 'unavailable') return;

    setCurrentLocale(preference.locale ?? resolveLocale(getBrowserLanguages()));
    setPreferenceState(
      preference.locale === undefined ? { kind: 'automatic' } : { kind: 'manual' },
    );
  }, []);

  const setLocale = useCallback((nextLocale: SupportedLocale) => {
    const storage = browserStorage();
    if (storage === undefined) {
      setCurrentLocale(nextLocale);
      setPreferenceState({ kind: 'local-selection' });
      return;
    }

    try {
      storage.setItem(consoleLocalePreferenceKey, nextLocale);
      setCurrentLocale(nextLocale);
      setPreferenceState({ kind: 'manual' });
    } catch {
      // 写入受限时本页选择仍可用；后续只能由一次成功读取的共享值取代。
      setCurrentLocale(nextLocale);
      setPreferenceState({ kind: 'local-selection' });
    }
  }, []);

  const value = useMemo<ConsoleLocaleContextValue>(
    () => ({ locale, enabledLocales: supportedLocales, setLocale }),
    [locale, setLocale],
  );

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === consoleLocalePreferenceKey || event.key === null) {
        refreshLocaleFromStorage();
      }
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') refreshLocaleFromStorage();
    };
    const onLanguageChange = () => {
      if (preferenceState.kind === 'automatic') {
        setCurrentLocale(resolveLocale(getBrowserLanguages()));
      }
    };

    window.addEventListener('storage', onStorage);
    window.addEventListener('languagechange', onLanguageChange);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('languagechange', onLanguageChange);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [preferenceState.kind, refreshLocaleFromStorage]);

  return <ConsoleLocaleContext.Provider value={value}>{children}</ConsoleLocaleContext.Provider>;
}

export function useConsoleLocale(): ConsoleLocaleContextValue {
  const value = useContext(ConsoleLocaleContext);
  if (value === undefined) {
    throw new Error('ConsoleLocaleProvider is required before rendering a Console.');
  }
  return value;
}

export function ConsoleLocaleSelector() {
  const { enabledLocales, locale, setLocale } = useConsoleLocale();
  const onValueChange = useCallback(
    (value: string) => {
      if (isSupportedLocale(value)) setLocale(value);
    },
    [setLocale],
  );

  return (
    <div className="sf-console-locale-control">
      <SelectField
        id="console-locale"
        label="Language / 语言"
        value={locale}
        options={localeOptions.filter((option) => enabledLocales.includes(option.value))}
        onValueChange={onValueChange}
      />
    </div>
  );
}

function resolveInitialConsoleLocaleState({
  storage = browserStorage(),
  browserLanguages = getBrowserLanguages(),
}: {
  readonly storage?: LocalePreferenceStorage;
  readonly browserLanguages?: readonly unknown[];
} = {}): InitialConsoleLocale {
  const preference = readLocalePreference(storage);
  if (preference.kind === 'unavailable') {
    return { locale: resolveLocale(browserLanguages), preferenceState: preference };
  }
  if (preference.locale === undefined) {
    return { locale: resolveLocale(browserLanguages), preferenceState: { kind: 'automatic' } };
  }
  return { locale: preference.locale, preferenceState: { kind: 'manual' } };
}

function readLocalePreference(
  storage: LocalePreferenceStorage | undefined,
):
  | { readonly kind: 'available'; readonly locale?: SupportedLocale }
  | { readonly kind: 'unavailable' } {
  if (storage === undefined) return { kind: 'unavailable' };
  try {
    const preference = storage.getItem(consoleLocalePreferenceKey);
    return isSupportedLocale(preference)
      ? { kind: 'available', locale: preference }
      : { kind: 'available' };
  } catch {
    return { kind: 'unavailable' };
  }
}

function browserStorage(): LocalePreferenceWriteStorage | undefined {
  if (typeof window === 'undefined') return undefined;
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

function getBrowserLanguages(): readonly unknown[] {
  if (typeof navigator === 'undefined') return [];
  return navigator.languages.length > 0 ? navigator.languages : [navigator.language];
}
