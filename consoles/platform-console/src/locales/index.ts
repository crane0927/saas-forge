import type { App } from 'vue';
import { watch } from 'vue';
import { createI18n, type MessageContext } from 'vue-i18n';
import { IntlMessageFormat } from 'intl-messageformat';
import zhCN from './zh-CN.json';
import enUS from './en-US.json';
export type Locale = 'zh-CN' | 'en-US';
const supported = (value: unknown): value is Locale => value === 'zh-CN' || value === 'en-US';
function browserLocale(): Locale {
  for (const language of navigator.languages) {
    if (/^zh(?:-|$)/i.test(language)) return 'zh-CN';
    if (/^en(?:-|$)/i.test(language)) return 'en-US';
  }
  return 'en-US';
}
let automatic = true;
function preference(): Locale {
  try {
    const saved = localStorage.getItem('sf:ui:locale');
    automatic = !supported(saved);
    if (supported(saved)) return saved;
  } catch {
    /* 使用浏览器语言。 */
  }
  return browserLocale();
}
const i18n = createI18n({
  legacy: false,
  locale: preference(),
  fallbackLocale: 'en-US',
  messages: { 'zh-CN': zhCN, 'en-US': enUS },
  messageCompiler(message, { locale, key }) {
    if (typeof message !== 'string') return () => key;
    const formatter = new IntlMessageFormat(message, locale, undefined, { ignoreTag: true });
    return (context: MessageContext) =>
      String(formatter.format(context.values as Record<string, string | number>));
  },
});
export const locale = i18n.global.locale;
export const $t = i18n.global.t;
export function setLocale(value: unknown) {
  if (!supported(value)) return;
  locale.value = value;
  automatic = false;
  try {
    localStorage.setItem('sf:ui:locale', value);
  } catch {
    /* 本页选择仍然有效。 */
  }
}
export function setupI18n(app: App) {
  app.use(i18n);
  const stop = watch(
    locale,
    (value) => {
      document.documentElement.lang = value;
    },
    { immediate: true },
  );
  const storage = (event: StorageEvent) => {
    if (event.key === 'sf:ui:locale' || event.key === null) locale.value = preference();
  };
  const language = () => {
    if (automatic) locale.value = browserLocale();
  };
  window.addEventListener('storage', storage);
  window.addEventListener('languagechange', language);
  app.onUnmount(() => {
    stop();
    window.removeEventListener('storage', storage);
    window.removeEventListener('languagechange', language);
  });
}
