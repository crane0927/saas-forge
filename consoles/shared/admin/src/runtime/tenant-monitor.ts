import { onScopeDispose, ref } from 'vue';
import type { AuthenticationRuntime } from '@saas-forge/app-runtime';

export function useTenantMonitor(runtime: AuthenticationRuntime) {
  const unavailable = ref(false);
  const ended = ref(false);
  if (runtime.intent !== 'TENANT') return { unavailable, ended };
  let worker: Worker;
  let current = true;
  let controller: AbortController | undefined;
  let sequence = 0;
  try {
    worker = new Worker(new URL('./tenant-session-monitor.worker.ts', import.meta.url), {
      type: 'module',
    });
  } catch {
    unavailable.value = true;
    return { unavailable, ended };
  }
  worker.onerror = (event) => {
    event.preventDefault();
    unavailable.value = true;
    controller?.abort();
  };
  const check = () => {
    const state = runtime.getState();
    if (
      controller ||
      state.status !== 'authenticated' ||
      (state.transition !== null &&
        !(state.transition === 'sessionSync' && state.synchronizationProblem))
    )
      return;
    controller = new AbortController();
    const active = controller;
    const id = ++sequence;
    worker.postMessage({ type: 'start', sequence: id });
    void runtime
      .checkTenantSession(active.signal)
      .then(() => {
        if (current && runtime.getState().status === 'anonymous') ended.value = true;
      })
      .finally(() => {
        if (current) worker.postMessage({ type: 'finish', sequence: id });
        controller = undefined;
      });
  };
  const visible = () => {
    if (document.visibilityState === 'visible') check();
  };
  worker.onmessage = (event: MessageEvent<{ type: string; sequence?: number }>) => {
    if (!current) return;
    if (event.data.type === 'tick') check();
    else if (event.data.sequence === sequence) controller?.abort();
  };
  window.addEventListener('focus', check);
  window.addEventListener('online', check);
  window.addEventListener('pageshow', check);
  document.addEventListener('visibilitychange', visible);
  onScopeDispose(() => {
    current = false;
    worker.terminate();
    controller?.abort();
    window.removeEventListener('focus', check);
    window.removeEventListener('online', check);
    window.removeEventListener('pageshow', check);
    document.removeEventListener('visibilitychange', visible);
  });
  return { unavailable, ended };
}
