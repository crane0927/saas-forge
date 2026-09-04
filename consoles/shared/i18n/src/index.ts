import IntlMessageFormat from 'intl-messageformat';

export const supportedLocaleRegistry = [
  { locale: 'zh-CN', selfName: '简体中文', language: 'zh' },
  { locale: 'en-US', selfName: 'English', language: 'en' },
] as const;

export type SupportedLocale = (typeof supportedLocaleRegistry)[number]['locale'];
export type MessageValues = Readonly<Record<string, string | number | Date>>;
export type MessageCatalog = Readonly<Record<string, string>>;

export const supportedLocales = supportedLocaleRegistry.map(
  ({ locale }) => locale,
) as readonly SupportedLocale[];
export const defaultSupportedLocale: SupportedLocale = 'en-US';

const supportedLocaleSet = new Set<string>(supportedLocales);
const safeRecoveryMessage: Readonly<Record<SupportedLocale, string>> = {
  'zh-CN': '暂时无法显示此内容。',
  'en-US': 'This content is temporarily unavailable.',
};

export function isSupportedLocale(value: unknown): value is SupportedLocale {
  return typeof value === 'string' && supportedLocaleSet.has(value);
}

/**
 * 按浏览器候选顺序先精确匹配，再回退到当前注册表中同一语言系列的首个 Locale。
 */
export function resolveLocale(candidates: readonly unknown[]): SupportedLocale {
  for (const candidate of candidates) {
    const canonicalCandidate = canonicalizeLocale(candidate);
    if (canonicalCandidate === undefined) continue;

    if (isSupportedLocale(canonicalCandidate)) {
      return canonicalCandidate;
    }

    const language = canonicalCandidate.split('-', 1)[0];
    const fallback = supportedLocaleRegistry.find((entry) => entry.language === language);
    if (fallback !== undefined) {
      return fallback.locale;
    }
  }
  return defaultSupportedLocale;
}

export function defineMessages<const Messages extends LocalizedMessageCatalog>(
  messages: Messages,
): Messages {
  return messages;
}

export function createTranslator<const Messages extends LocalizedMessageCatalog>({
  namespace,
  locale,
  messages,
}: {
  readonly namespace: string;
  readonly locale: SupportedLocale;
  readonly messages: Messages;
}): Translator<keyof Messages['en-US'] & string> {
  return {
    namespace,
    translate(key, values) {
      const currentMessage = messages[locale][key];
      const englishMessage = messages['en-US'][key];
      return (
        formatMessage(currentMessage, locale, values) ??
        formatMessage(englishMessage, 'en-US', values) ??
        safeRecoveryMessage[locale]
      );
    },
  };
}

export interface Translator<Key extends string> {
  readonly namespace: string;
  translate(key: Key, values?: MessageValues): string;
}

type LocalizedMessageCatalog = Readonly<Record<SupportedLocale, MessageCatalog>>;

function canonicalizeLocale(candidate: unknown): string | undefined {
  if (typeof candidate !== 'string' || candidate.trim() === '') return undefined;
  try {
    return Intl.getCanonicalLocales(candidate)[0];
  } catch {
    return undefined;
  }
}

function formatMessage(
  message: string | undefined,
  locale: SupportedLocale,
  values: MessageValues | undefined,
): string | undefined {
  if (message === undefined) return undefined;
  try {
    const result = new IntlMessageFormat(message, locale).format(values);
    return typeof result === 'string' ? result : undefined;
  } catch {
    return undefined;
  }
}
