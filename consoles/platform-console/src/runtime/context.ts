import { inject, type InjectionKey, type Ref } from 'vue';
import type { AuthenticationRuntime } from '@saas-forge/app-runtime';
import type { Locale } from '../locales';
export interface ApplicationContext {
  runtime: AuthenticationRuntime;
  locale: Ref<Locale>;
  requestExit: () => Promise<boolean>;
  guards: Map<symbol, () => boolean>;
}
export const applicationContextKey: InjectionKey<ApplicationContext> =
  Symbol('platform-application');
export function useApplication() {
  const context = inject(applicationContextKey);
  if (!context) throw new Error('Platform application context required');
  return context;
}
