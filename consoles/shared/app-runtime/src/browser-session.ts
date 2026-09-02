import type { AuthenticationIntent } from './authentication-runtime';

interface SharedAuthentication {
  readonly event: 'refresh-succeeded';
  readonly contextType: AuthenticationIntent;
  readonly generation: number;
  readonly accessToken: string;
  readonly expiresAt: number;
}
interface SharedSessionEnd {
  readonly event: 'session-ended';
  readonly contextType: AuthenticationIntent;
  readonly generation: number;
}
type SessionMessage = SharedAuthentication | SharedSessionEnd;

interface BrowserRealm {
  readonly navigator?: { readonly locks?: LockManager };
  readonly BroadcastChannel?: typeof BroadcastChannel;
  readonly localStorage?: Storage;
  addEventListener?: (type: string, listener: (event: Event) => void) => void;
}

/** 仅使用浏览器原子锁；协调状态绝不包含持久化 Token。 */
export function createBrowserSession(
  realm: object,
  apiOrigin: string,
  intent: AuthenticationIntent,
  receive: (message: SessionMessage) => void | Promise<void>,
  logoutPending: () => void,
  invalidate: () => void,
  now: () => number,
) {
  const browser = realm as BrowserRealm;
  let locks: LockManager | undefined;
  let storage: Storage | undefined;
  let channel: BroadcastChannel | undefined;
  let disabled = false;
  const isDisabled = () => disabled;
  let pending = false;
  let generation = 0;
  const name = `sf:session:${apiOrigin}:${intent}`;
  const generationKey = `${name}:generation`;
  const pendingKey = `${name}:logoutPending`;
  const disable = () => {
    disabled = true;
    try {
      channel?.close();
    } catch {
      /* 能力故障不改变已完成的服务端操作。 */
    }
  };
  const readGeneration = () => {
    try {
      const value = Number(storage?.getItem(generationKey) ?? generation);
      if (!Number.isSafeInteger(value) || value < 0) {
        disable();
        return generation;
      }
      return value;
    } catch {
      disable();
      return generation;
    }
  };
  try {
    storage = browser.localStorage;
    generation = readGeneration();
    locks = browser.navigator?.locks;
    if (
      !isDisabled() &&
      locks !== undefined &&
      storage !== undefined &&
      browser.BroadcastChannel !== undefined
    ) {
      channel = new browser.BroadcastChannel(name);
    }
  } catch {
    disable();
  }
  const available = () =>
    !disabled && locks !== undefined && channel !== undefined && storage !== undefined;
  const isLogoutPending = () => {
    try {
      return storage === undefined ? pending : storage.getItem(pendingKey) === 'true';
    } catch {
      disable();
      return pending;
    }
  };
  const writePending = (value: boolean) => {
    pending = value;
    try {
      storage?.setItem(pendingKey, String(value));
    } catch {
      disable();
    }
  };
  let watermark = generation;
  let receipt = Promise.resolve();
  const arrivals = new Set<() => void>();
  const nextGeneration = () => {
    if (!available()) return generation;
    generation = Math.max(generation, readGeneration()) + 1;
    if (!Number.isSafeInteger(generation)) {
      disable();
      return generation;
    }
    try {
      storage?.setItem(generationKey, String(generation));
    } catch {
      disable();
    }
    return generation;
  };
  try {
    browser.addEventListener?.('storage', (event) => {
      if ((event as StorageEvent).key === pendingKey && isLogoutPending()) logoutPending();
      if ((event as StorageEvent).key === generationKey) {
        const latest = readGeneration();
        if (latest > Math.max(generation, watermark)) {
          watermark = latest;
          invalidate();
        }
      }
    });
  } catch {
    disable();
  }
  if (channel !== undefined) {
    channel.onmessage = ({ data }: MessageEvent<unknown>) => {
      if (!available() || !isSessionMessage(data, intent) || data.generation <= generation) return;
      if (data.generation < watermark) return;
      if (data.event === 'refresh-succeeded' && data.expiresAt <= now()) return;
      if (isLogoutPending() && data.event === 'refresh-succeeded') return;
      generation = data.generation;
      receipt = Promise.resolve(receive(data));
      for (const arrival of arrivals) arrival();
    };
    channel.onmessageerror = disable;
  }
  const send = (
    message: Omit<SharedAuthentication, 'generation'> | Omit<SharedSessionEnd, 'generation'>,
  ) => {
    if (!available()) return;
    const next = nextGeneration();
    if (!available()) return;
    try {
      channel?.postMessage({ ...message, generation: next });
    } catch {
      disable();
    }
  };

  return {
    changed: () => {
      nextGeneration();
    },
    isLogoutPending,
    requestLogout: () => {
      writePending(true);
      logoutPending();
    },
    ended: () => {
      writePending(false);
      send({ event: 'session-ended', contextType: intent });
    },
    run: async <T>(work: (changed: boolean) => Promise<T> | T): Promise<T> => {
      const expectedGeneration = generation;
      if (!available() || locks === undefined) return work(false);
      let execution: Promise<T> | undefined;
      const execute = async () => {
        const latest = readGeneration();
        if (latest > generation) {
          // 锁释放与消息派发来自不同任务队列。仅为广播交接等待一秒，
          // 发送页崩溃或消息遗失后仍能由当前 Cookie 与 IAM Lease 恢复，不伪造互斥。
          await new Promise<void>((resolve) => {
            const finish = () => {
              clearTimeout(timer);
              arrivals.delete(arrived);
              resolve();
            };
            const arrived = () => {
              if (generation >= latest) finish();
            };
            const timer = setTimeout(finish, 1_000);
            arrivals.add(arrived);
          });
          generation = Math.max(generation, latest);
        }
        await receipt;
        return work(generation > expectedGeneration);
      };
      try {
        return await locks.request(name, () => {
          execution = execute();
          return execution;
        });
      } catch {
        disable();
        // request 在 callback 已开始后失败也不能重放变更，否则可能重复登录/切换。
        return execution === undefined ? work(false) : execution;
      }
    },
    authenticated: (accessToken: string, expiresAt: number) => {
      send({ event: 'refresh-succeeded', contextType: intent, accessToken, expiresAt });
    },
  };
}

function isSessionMessage(value: unknown, intent: AuthenticationIntent): value is SessionMessage {
  if (typeof value !== 'object' || value === null) return false;
  const message = value as Record<string, unknown>;
  if (
    message.contextType !== intent ||
    typeof message.generation !== 'number' ||
    !Number.isSafeInteger(message.generation) ||
    message.generation <= 0
  )
    return false;
  if (message.event === 'session-ended') return Object.keys(message).length === 3;
  return (
    Object.keys(message).length === 5 &&
    message.event === 'refresh-succeeded' &&
    message.contextType === intent &&
    typeof message.generation === 'number' &&
    Number.isSafeInteger(message.generation) &&
    message.generation > 0 &&
    typeof message.accessToken === 'string' &&
    message.accessToken.length > 0 &&
    typeof message.expiresAt === 'number' &&
    Number.isFinite(message.expiresAt)
  );
}
