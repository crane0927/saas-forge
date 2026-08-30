import {
  loadRuntimeConfig,
  runtimeConfigFailure,
  type RuntimeConfig,
  type RuntimeConfigError,
  type RuntimeConfigResult,
} from './runtime-config';

export type RuntimeConfigLoader = () => Promise<RuntimeConfigResult>;

export type BootstrapState =
  | { readonly status: 'idle'; readonly attempt: 0 }
  | { readonly status: 'loading'; readonly attempt: number }
  | { readonly status: 'ready'; readonly attempt: number; readonly config: RuntimeConfig }
  | { readonly status: 'failed'; readonly attempt: number; readonly error: RuntimeConfigError };

export type BootstrapListener = (state: BootstrapState) => void;

export interface RuntimeConfigBootstrap {
  getState(): BootstrapState;
  subscribe(listener: BootstrapListener): () => void;
  start(): Promise<BootstrapState>;
  retry(): Promise<BootstrapState>;
}

/**
 * 创建显式启动、只允许用户触发重试的 Runtime Config Bootstrap 状态控制器。
 * `start` 不会在失败后隐式重试，`retry` 仅在 `failed` 状态发起新请求。
 */
export function createRuntimeConfigBootstrap(
  loader: RuntimeConfigLoader = loadRuntimeConfig,
): RuntimeConfigBootstrap {
  let state: BootstrapState = { status: 'idle', attempt: 0 };
  let inFlight: Promise<BootstrapState> | undefined;
  const listeners = new Set<BootstrapListener>();

  const publish = (nextState: BootstrapState): void => {
    state = nextState;
    for (const listener of listeners) {
      listener(state);
    }
  };

  const load = (): Promise<BootstrapState> => {
    if (inFlight !== undefined) {
      return inFlight;
    }

    const attempt = state.attempt + 1;
    publish({ status: 'loading', attempt });

    inFlight = Promise.resolve()
      .then(loader)
      .catch(() => runtimeConfigFailure('CONFIG_UNAVAILABLE'))
      .then((result) => {
        const nextState: BootstrapState = result.ok
          ? { status: 'ready', attempt, config: result.config }
          : { status: 'failed', attempt, error: result.error };
        publish(nextState);
        return nextState;
      })
      .finally(() => {
        inFlight = undefined;
      });

    return inFlight;
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    start: () =>
      state.status === 'idle' || state.status === 'loading' ? load() : Promise.resolve(state),
    retry: () => (state.status === 'failed' ? load() : Promise.resolve(state)),
  };
}
