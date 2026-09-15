import { computed } from 'vue';
import { createTranslator } from '@saas-forge/i18n';
import { useConsole } from '@saas-forge/admin';
import { platformMessages } from './messages';
export function usePlatform() {
  const context = useConsole();
  const translator = computed(() =>
    createTranslator({
      namespace: '@saas-forge/platform-console',
      locale: context.locale.value,
      messages: platformMessages,
    }),
  );
  return {
    ...context,
    client: context.runtime.client,
    t: (key: keyof (typeof platformMessages)['zh-CN'], values?: Record<string, string | number>) =>
      translator.value.translate(key, values),
  };
}
