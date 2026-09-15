import {
  computed,
  inject,
  onScopeDispose,
  ref,
  shallowRef,
  watch,
  type InjectionKey,
  type Ref,
} from 'vue';
import {
  createTranslator,
  isSupportedLocale,
  resolveLocale,
  type SupportedLocale,
} from '@saas-forge/i18n';
import type { AuthenticationRuntime, ConsoleApiResult } from '@saas-forge/app-runtime';
import { shellMessages } from '../messages/authentication';

export interface ConsoleContext {
  runtime: AuthenticationRuntime;
  locale: Ref<SupportedLocale>;
  requestExit: () => Promise<boolean>;
  guards: Map<symbol, () => boolean>;
}
export const consoleContextKey: InjectionKey<ConsoleContext> = Symbol('console');
export function useConsole() {
  const context = inject(consoleContextKey);
  if (!context) throw new Error('Console context required');
  return context;
}
export function useShellText() {
  const { locale } = useConsole();
  const translator = computed(() =>
    createTranslator({
      namespace: '@saas-forge/admin',
      locale: locale.value,
      messages: shellMessages,
    }),
  );
  return (key: keyof (typeof shellMessages)['zh-CN'], values?: Record<string, string | number>) =>
    translator.value.translate(key, values);
}
export function useFormExitGuard(dirty: () => boolean) {
  const { guards } = useConsole();
  const key = Symbol('form');
  guards.set(key, dirty);
  onScopeDispose(() => guards.delete(key));
}

export function useLocale() {
  const preferenceKey = 'sf:ui:locale';
  const automatic = ref(true);
  const locale = ref<SupportedLocale>(resolveLocale(navigator.languages));
  function refresh() {
    try {
      const value = localStorage.getItem(preferenceKey);
      automatic.value = !isSupportedLocale(value);
      locale.value = isSupportedLocale(value) ? value : resolveLocale(navigator.languages);
    } catch {
      /* 存储不可用时保留本页语言选择。 */
    }
  }
  refresh();
  function setLocale(value: unknown) {
    if (!isSupportedLocale(value)) return;
    locale.value = value;
    automatic.value = false;
    try {
      localStorage.setItem(preferenceKey, value);
    } catch {
      /* 本页选择仍然有效。 */
    }
  }
  const storage = (event: StorageEvent) => {
    if (event.key === preferenceKey || event.key === null) refresh();
  };
  const visible = () => {
    if (document.visibilityState === 'visible') refresh();
  };
  const language = () => {
    if (automatic.value) locale.value = resolveLocale(navigator.languages);
  };
  window.addEventListener('storage', storage);
  window.addEventListener('languagechange', language);
  document.addEventListener('visibilitychange', visible);
  watch(
    locale,
    (value) => {
      document.documentElement.lang = value;
    },
    { immediate: true },
  );
  onScopeDispose(() => {
    window.removeEventListener('storage', storage);
    window.removeEventListener('languagechange', language);
    document.removeEventListener('visibilitychange', visible);
  });
  return { locale, setLocale };
}

/** 独立区块保留成功快照，同时将读取失败作为操作禁用条件。 */
export function useRead<T>(
  load: (signal: AbortSignal) => Promise<ConsoleApiResult<T>>,
  dependencies: () => unknown = () => null,
  preserve = false,
) {
  const value = shallowRef<T>();
  const problem = ref<string>();
  const loading = ref(true);
  const revision = ref(0);
  watch(
    () => [dependencies(), revision.value],
    async (_, __, cleanup) => {
      const controller = new AbortController();
      cleanup(() => controller.abort());
      loading.value = true;
      problem.value = undefined;
      if (!preserve) value.value = undefined;
      try {
        const result = await load(controller.signal);
        if (controller.signal.aborted) return;
        if (result.ok) value.value = result.value;
        else problem.value = result.problem.code;
      } catch {
        if (!controller.signal.aborted) problem.value = 'NETWORK_UNAVAILABLE';
      } finally {
        if (!controller.signal.aborted) loading.value = false;
      }
    },
    { immediate: true, flush: 'sync' },
  );
  return {
    value,
    problem,
    loading,
    ready: computed(() => !loading.value && !problem.value && value.value !== undefined),
    refresh: () => {
      revision.value++;
    },
  };
}

export async function readAll<T>(
  load: (
    cursor?: string,
  ) => Promise<
    ConsoleApiResult<{ items: readonly T[]; hasMore: boolean; nextCursor?: string | null }>
  >,
  signal: AbortSignal,
): Promise<ConsoleApiResult<readonly T[]>> {
  const items: T[] = [];
  const visited = new Set<string>();
  let cursor: string | undefined;
  for (;;) {
    const page = await load(cursor);
    if (signal.aborted) return { ok: false, problem: { code: 'REQUEST_CANCELLED' } };
    if (!page.ok) return page;
    items.push(...page.value.items);
    if (!page.value.hasMore) return { ok: true, value: items };
    if (!page.value.nextCursor || visited.has(page.value.nextCursor))
      return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
    cursor = page.value.nextCursor;
    visited.add(cursor);
  }
}

export function useMutation() {
  const busy = ref(false);
  const unknown = ref(false);
  const problem = ref<string>();
  let controller: AbortController | undefined;
  onScopeDispose(() => controller?.abort());
  async function run<T>(
    operation: (signal: AbortSignal) => Promise<ConsoleApiResult<T>>,
    recovering = false,
  ) {
    if (busy.value || (unknown.value && !recovering)) return;
    controller = new AbortController();
    const current = controller;
    busy.value = true;
    problem.value = undefined;
    try {
      const result = await operation(current.signal);
      if (current.signal.aborted) return;
      unknown.value = !result.ok;
      if (!result.ok) problem.value = result.problem.code;
      return result;
    } catch {
      if (!current.signal.aborted) {
        unknown.value = true;
        problem.value = 'NETWORK_UNAVAILABLE';
      }
    } finally {
      if (!current.signal.aborted) {
        busy.value = false;
        controller = undefined;
      }
    }
  }
  function resolved() {
    unknown.value = false;
    problem.value = undefined;
  }
  return { busy, unknown, problem, run, resolved };
}
