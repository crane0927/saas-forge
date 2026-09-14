import { computed, onScopeDispose, shallowReadonly, shallowRef } from 'vue';
import type { AuthenticationRuntime, AuthenticationState } from '@saas-forge/app-runtime';

/** Vue 仅观察 Runtime 的会话投影，凭据仍由 Runtime 私有持有。 */
export function useAuthenticationRuntime(
  runtime: Pick<AuthenticationRuntime, 'intent' | 'getState' | 'subscribe'>,
) {
  const state = shallowRef<AuthenticationState>(runtime.getState());
  const unsubscribe = runtime.subscribe((next) => {
    state.value = next;
  });
  onScopeDispose(unsubscribe);
  const businessReady = computed(() => {
    const current = state.value;
    return (
      current.status === 'authenticated' &&
      !['recover', 'sessionSync', 'tenantSwitchRefresh', 'logout'].includes(
        current.transition ?? '',
      ) &&
      current.synchronizationProblem === undefined &&
      (runtime.intent === 'PLATFORM' || current.tenantContext !== undefined)
    );
  });
  return { state: shallowReadonly(state), businessReady };
}
