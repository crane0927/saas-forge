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

const decimalPattern = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?$/;
const calendarDatePattern = /^(\d{4})-(\d{2})-(\d{2})$/;
const instantPattern =
  /^(\d{4})-(\d{2})-(\d{2})T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)$/;

export interface FormatDateInput {
  /** REST 日历日期原值，格式必须为 YYYY-MM-DD。 */
  readonly value: string;
  readonly locale: SupportedLocale;
}

export interface FormatNumberInput {
  /** 普通十进制字符串；不接受指数、正号、分组符或空白，并保留全部小数位。 */
  readonly value: string;
  readonly locale: SupportedLocale;
}

export interface FormatInstantInput extends FormatDateInput {
  /** 未提供时使用浏览器默认时区；提供时必须是 Intl 支持的 IANA 时区。 */
  readonly timeZone?: string;
}

export interface FormatMoneyInput extends FormatNumberInput {
  /** 业务明确提供的三字母币种代码。 */
  readonly currency: string;
}

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

/** 日历日期始终按日期本身格式化，不把 API 原值解释为浏览器本地时间点。 */
export function formatDate({ value, locale }: FormatDateInput): string {
  if (!isValidCalendarDate(value)) return safeRecoveryMessage[locale];
  try {
    return new Intl.DateTimeFormat(locale, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      timeZone: 'UTC',
    }).format(new Date(`${value}T00:00:00.000Z`));
  } catch {
    return safeRecoveryMessage[locale];
  }
}

/** 时间点必须是带 Z 或数值偏移的 RFC 3339 值；异常输入返回本地化安全恢复文案。 */
export function formatInstant({ value, locale, timeZone }: FormatInstantInput): string {
  if (!matchesEntireValue(instantPattern, value) || !isValidCalendarDate(value.slice(0, 10))) {
    return safeRecoveryMessage[locale];
  }
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return safeRecoveryMessage[locale];
  try {
    return new Intl.DateTimeFormat(locale, {
      dateStyle: 'medium',
      timeStyle: 'medium',
      timeZone,
    }).format(instant);
  } catch {
    return safeRecoveryMessage[locale];
  }
}

/** 只用 BigInt 处理整数分组，避免十进制字符串隐式转换为浮点数。 */
export function formatNumber({ value, locale }: FormatNumberInput): string {
  const decimal = parseDecimal(value);
  if (decimal === undefined) return safeRecoveryMessage[locale];
  try {
    return formatParsedDecimal(decimal, locale);
  } catch {
    return safeRecoveryMessage[locale];
  }
}

/** 金额只添加 Locale 和币种表达，不补零、截断或执行业务舍入。 */
export function formatMoney({ value, locale, currency }: FormatMoneyInput): string {
  const decimal = parseDecimal(value);
  if (decimal === undefined || !matchesEntireValue(/^[A-Za-z]{3}$/, currency)) {
    return safeRecoveryMessage[locale];
  }
  try {
    const formatter = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currency.toUpperCase(),
      currencyDisplay: 'symbol',
      minimumFractionDigits: 0,
      maximumFractionDigits: 0,
    });
    const unsignedValue = formatParsedDecimal({ ...decimal, negative: false }, locale);
    let insertedValue = false;
    return formatter
      .formatToParts(decimal.negative ? -0 : 0)
      .flatMap((part) => {
        if (part.type === 'integer') {
          if (insertedValue) return [];
          insertedValue = true;
          return [unsignedValue];
        }
        if (
          part.type === 'group' ||
          part.type === 'decimal' ||
          part.type === 'fraction' ||
          part.type === 'compact' ||
          part.type === 'exponentInteger' ||
          part.type === 'exponentMinusSign' ||
          part.type === 'exponentSeparator'
        ) {
          return [];
        }
        return [part.value];
      })
      .join('');
  } catch {
    return safeRecoveryMessage[locale];
  }
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

interface ParsedDecimal {
  readonly negative: boolean;
  readonly integer: string;
  readonly fraction?: string;
}

function parseDecimal(value: string): ParsedDecimal | undefined {
  const match = decimalPattern.exec(value);
  if (match === null || match[0] !== value) return undefined;
  return {
    negative: match[1] === '-',
    integer: match[2],
    fraction: match[3],
  };
}

function formatParsedDecimal(decimal: ParsedDecimal, locale: SupportedLocale): string {
  const formatter = new Intl.NumberFormat(locale, {
    useGrouping: true,
    maximumFractionDigits: 0,
  });
  const integer = formatter.format(BigInt(decimal.integer));
  const minusSign = decimal.negative
    ? (formatter.formatToParts(-1).find((part) => part.type === 'minusSign')?.value ?? '-')
    : '';
  if (decimal.fraction === undefined) return `${minusSign}${integer}`;

  const decimalSeparator =
    new Intl.NumberFormat(locale).formatToParts(1.1).find((part) => part.type === 'decimal')
      ?.value ?? '.';
  const localizedFraction = decimal.fraction
    .split('')
    .map((digit) => formatter.format(BigInt(digit)))
    .join('');
  return `${minusSign}${integer}${decimalSeparator}${localizedFraction}`;
}

function isValidCalendarDate(value: string): boolean {
  if (!matchesEntireValue(calendarDatePattern, value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function matchesEntireValue(pattern: RegExp, value: string): boolean {
  return pattern.exec(value)?.[0] === value;
}

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
