export const RUNTIME_CONFIG_PATH = '/runtime-config.json';

export type RuntimeConfigErrorCode =
  'CONFIG_NOT_FOUND' | 'CONFIG_UNAVAILABLE' | 'CONFIG_INVALID' | 'API_ORIGIN_INVALID';

export interface RuntimeConfig {
  readonly schemaVersion: 1;
  readonly apiBaseUrl: string;
}

export interface RuntimeConfigError {
  readonly code: RuntimeConfigErrorCode;
}

export type RuntimeConfigResult =
  | { readonly ok: true; readonly config: RuntimeConfig }
  | { readonly ok: false; readonly error: RuntimeConfigError };

export type RuntimeConfigFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

const CONFIG_KEYS = new Set(['schemaVersion', 'apiBaseUrl']);

export function parseRuntimeConfig(input: unknown): RuntimeConfigResult {
  if (!isExactConfigObject(input)) {
    return failure('CONFIG_INVALID');
  }

  if (input.schemaVersion !== 1 || typeof input.apiBaseUrl !== 'string') {
    return failure('CONFIG_INVALID');
  }

  const apiOrigin = parseHttpsOrigin(input.apiBaseUrl);
  if (apiOrigin === undefined) {
    return failure('API_ORIGIN_INVALID');
  }

  return {
    ok: true,
    config: {
      schemaVersion: 1,
      apiBaseUrl: apiOrigin,
    },
  };
}

export async function loadRuntimeConfig(
  fetchConfig: RuntimeConfigFetch = globalThis.fetch,
): Promise<RuntimeConfigResult> {
  let response: Response;
  try {
    response = await fetchConfig(RUNTIME_CONFIG_PATH, {
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
    });
  } catch {
    return failure('CONFIG_UNAVAILABLE');
  }

  if (response.status === 404) {
    return failure('CONFIG_NOT_FOUND');
  }
  if (!response.ok) {
    return failure('CONFIG_UNAVAILABLE');
  }

  let input: unknown;
  try {
    input = await response.json();
  } catch {
    return failure('CONFIG_INVALID');
  }

  return parseRuntimeConfig(input);
}

function isExactConfigObject(
  input: unknown,
): input is Record<'schemaVersion' | 'apiBaseUrl', unknown> {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return false;
  }

  const keys = Object.keys(input);
  return keys.length === CONFIG_KEYS.size && keys.every((key) => CONFIG_KEYS.has(key));
}

function parseHttpsOrigin(value: string): string | undefined {
  if (value.length === 0 || value !== value.trim()) {
    return undefined;
  }

  const authorityAndPath = value.slice('https://'.length);
  const pathStart = authorityAndPath.indexOf('/');
  if (pathStart !== -1 && pathStart !== authorityAndPath.length - 1) {
    return undefined;
  }

  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return undefined;
  }

  if (
    url.protocol !== 'https:' ||
    url.username !== '' ||
    url.password !== '' ||
    url.pathname !== '/' ||
    url.search !== '' ||
    url.hash !== ''
  ) {
    return undefined;
  }

  // URL 会把空查询或 Fragment 规范化为空字符串，必须继续核对完整序列化结果。
  if (url.href !== `${url.origin}/`) {
    return undefined;
  }

  return url.origin;
}

export function runtimeConfigFailure(code: RuntimeConfigErrorCode): RuntimeConfigResult {
  return failure(code);
}

function failure(code: RuntimeConfigErrorCode): RuntimeConfigResult {
  return { ok: false, error: { code } };
}
