import { isSupportedLocale, resolveLocale, type SupportedLocale } from '@saas-forge/i18n';
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

export const consoleLocalePreferenceKey = 'sf:ui:locale';

export interface ConsoleLocaleProviderProps {
  readonly children: ReactNode;
  readonly initialLocale?: SupportedLocale;
}

interface ConsoleLocaleContextValue {
  readonly locale: SupportedLocale;
}

interface LocalePreferenceStorage {
  getItem(key: string): string | null;
}

const ConsoleLocaleContext = createContext<ConsoleLocaleContextValue | undefined>(undefined);

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
  const preference = readLocalePreference(storage);
  return preference === undefined ? resolveLocale(browserLanguages) : preference;
}

export function ConsoleLocaleProvider({ children, initialLocale }: ConsoleLocaleProviderProps) {
  const [locale] = useState(() => initialLocale ?? resolveInitialConsoleLocale());
  const value = useMemo<ConsoleLocaleContextValue>(() => ({ locale }), [locale]);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  return <ConsoleLocaleContext.Provider value={value}>{children}</ConsoleLocaleContext.Provider>;
}

export function useConsoleLocale(): ConsoleLocaleContextValue {
  const value = useContext(ConsoleLocaleContext);
  if (value === undefined) {
    throw new Error('ConsoleLocaleProvider is required before rendering a Console.');
  }
  return value;
}

function readLocalePreference(
  storage: LocalePreferenceStorage | undefined,
): SupportedLocale | undefined {
  if (storage === undefined) return undefined;
  try {
    const preference = storage.getItem(consoleLocalePreferenceKey);
    return isSupportedLocale(preference) ? preference : undefined;
  } catch {
    return undefined;
  }
}

function browserStorage(): LocalePreferenceStorage | undefined {
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
