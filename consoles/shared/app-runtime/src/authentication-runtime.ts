import {
  AuthenticationApi,
  Configuration,
  FetchError,
  OAuthClientsApi,
  ResponseError,
  type CreateOAuthClientRequest,
  type OAuthClientDetail,
  type OAuthClientSecretResult,
} from '@saas-forge/api-client';

import type { RuntimeConfig, RuntimeConfigError, RuntimeConfigResult } from './runtime-config';
import { createBrowserSession } from './browser-session';

export type AuthenticationIntent = 'PLATFORM' | 'TENANT';
export type AuthenticationTransition =
  | 'login'
  | 'recover'
  | 'passwordChange'
  | 'contextSelection'
  | 'logout'
  | 'refresh'
  | 'tenantSwitch'
  | 'sessionSync'
  | 'tenantSwitchRefresh';

export interface AnonymousAuthenticationState {
  readonly status: 'anonymous';
  readonly transition: AuthenticationTransition | null;
}

export interface AuthenticatedAuthenticationState {
  readonly status: 'authenticated';
  readonly transition: AuthenticationTransition | null;
  readonly tenantContext?: TenantAuthenticationContext;
  readonly synchronizationProblem?: AuthenticationProblem;
}

export interface TenantBrandProfileSnapshot {
  readonly displayName: string;
  readonly logoUrl?: string;
  readonly faviconUrl?: string;
  readonly primaryColor: string;
  readonly accentColor: string;
}

export interface TenantAuthenticationContext extends MembershipCandidate {
  readonly accessibleMemberships: readonly MembershipCandidate[];
  readonly brandProfile?: TenantBrandProfileSnapshot;
}

export interface PasswordChangeRequiredAuthenticationState {
  readonly status: 'passwordChangeRequired';
  readonly transition: AuthenticationTransition | null;
}

export interface MembershipCandidate {
  readonly membershipId: string;
  readonly tenantId: string;
  readonly tenantDisplayName: string;
}

export interface ContextSelectionRequiredAuthenticationState {
  readonly status: 'contextSelectionRequired';
  readonly transition: AuthenticationTransition | null;
  readonly memberships: readonly MembershipCandidate[];
}

export interface LogoutPendingAuthenticationState {
  readonly status: 'logoutPending';
  readonly transition: AuthenticationTransition | null;
}

export type AuthenticationState =
  | AnonymousAuthenticationState
  | AuthenticatedAuthenticationState
  | PasswordChangeRequiredAuthenticationState
  | ContextSelectionRequiredAuthenticationState
  | LogoutPendingAuthenticationState;

export type AuthenticationListener = (state: AuthenticationState) => void;

export interface AuthenticationStateAction {
  readonly type: 'transition';
  readonly state: AuthenticationState;
}

export function authenticationReducer(
  _state: AuthenticationState,
  action: AuthenticationStateAction,
): AuthenticationState {
  return action.state;
}

export type AuthenticationFetch = (
  input: RequestInfo | URL,
  init?: RequestInit,
) => Promise<Response>;

export interface AuthenticationRuntimeOptions {
  /** 页面 Realm 身份；同一 Realm 的同一 Intent 始终返回同一 Runtime。 */
  readonly realm: object;
  readonly config: RuntimeConfig;
  readonly intent: AuthenticationIntent;
  readonly fetch: AuthenticationFetch;
  readonly now?: () => number;
  readonly createIdempotencyKey?: () => string;
  readonly initialLogoutPending?: boolean;
}

export type AuthenticationRuntimeCreationOptions = Omit<AuthenticationRuntimeOptions, 'config'>;

export interface LoginInput {
  readonly email: string;
  readonly password: string;
  readonly signal?: AbortSignal;
}

export interface ChangeInitialPasswordInput {
  readonly newPassword: string;
  readonly signal?: AbortSignal;
}

export interface SelectAuthenticationContextInput {
  readonly membershipId: string;
  readonly signal?: AbortSignal;
}

export interface SwitchTenantContextInput {
  readonly membershipId: string;
  readonly operationHandle?: IdempotentOperationHandle;
  readonly signal?: AbortSignal;
}

export type TenantSwitchResult =
  | { readonly ok: true; readonly state: AuthenticationState }
  | {
      readonly ok: false;
      readonly problem: AuthenticationProblem;
      readonly operationHandle?: IdempotentOperationHandle;
    };

export interface AuthenticationFieldError {
  readonly pointer: string;
  readonly code: string;
}

export interface AuthenticationProblem {
  readonly code: string;
  readonly status?: number;
  readonly traceId?: string;
  readonly retryAfterSeconds?: number;
  readonly fieldErrors?: readonly AuthenticationFieldError[];
}

export type AuthenticationOperationResult =
  | { readonly ok: true; readonly state: AuthenticationState }
  | { readonly ok: false; readonly problem: AuthenticationProblem };

export type ConsoleApiResult<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly problem: AuthenticationProblem };

declare const idempotentOperationHandleBrand: unique symbol;

export interface IdempotentOperationHandle {
  readonly [idempotentOperationHandleBrand]: true;
}

export type IdempotentConsoleApiResult<T> =
  | { readonly ok: true; readonly value: T }
  | {
      readonly ok: false;
      readonly problem: AuthenticationProblem;
      readonly operationHandle: IdempotentOperationHandle;
    };

export interface GetOAuthClientInput {
  readonly clientId: string;
  readonly signal?: AbortSignal;
}

export interface CreateOAuthClientInput {
  readonly request: CreateOAuthClientRequest;
  readonly operationHandle?: IdempotentOperationHandle;
  readonly signal?: AbortSignal;
}

export interface ConsoleApiClient {
  getOAuthClient(input: GetOAuthClientInput): Promise<ConsoleApiResult<OAuthClientDetail>>;
  createOAuthClient(
    input: CreateOAuthClientInput,
  ): Promise<IdempotentConsoleApiResult<OAuthClientSecretResult>>;
}

export interface AuthenticationRuntime {
  readonly intent: AuthenticationIntent;
  readonly client: ConsoleApiClient;
  getState(): AuthenticationState;
  subscribe(listener: AuthenticationListener): () => void;
  recover(signal?: AbortSignal): Promise<AuthenticationOperationResult>;
  retryRecovery(signal?: AbortSignal): Promise<AuthenticationOperationResult>;
  login(input: LoginInput): Promise<AuthenticationOperationResult>;
  changeInitialPassword(input: ChangeInitialPasswordInput): Promise<AuthenticationOperationResult>;
  selectAuthenticationContext(
    input: SelectAuthenticationContextInput,
  ): Promise<AuthenticationOperationResult>;
  switchTenantContext(input: SwitchTenantContextInput): Promise<TenantSwitchResult>;
  retryTenantSwitchRefresh(signal?: AbortSignal): Promise<AuthenticationOperationResult>;
  logout(signal?: AbortSignal): Promise<AuthenticationOperationResult>;
}

const realmRuntimes = new WeakMap<object, Map<AuthenticationIntent, AuthenticationRuntime>>();

export type AuthenticationRuntimeCreationResult =
  | { readonly ok: true; readonly runtime: AuthenticationRuntime }
  | { readonly ok: false; readonly error: RuntimeConfigError };

export function createAuthenticationRuntimeAfterConfig(
  result: RuntimeConfigResult,
  options: AuthenticationRuntimeCreationOptions,
): AuthenticationRuntimeCreationResult {
  if (!result.ok) {
    return { ok: false, error: result.error };
  }
  return { ok: true, runtime: createAuthenticationRuntime({ ...options, config: result.config }) };
}

function createAuthenticationRuntime(options: AuthenticationRuntimeOptions): AuthenticationRuntime {
  const existing = realmRuntimes.get(options.realm)?.get(options.intent);
  if (existing !== undefined) {
    return existing;
  }
  let state: AuthenticationState = options.initialLogoutPending
    ? { status: 'logoutPending', transition: null }
    : { status: 'anonymous', transition: null };
  let accessToken: string | undefined;
  let expiresAt: number | undefined;
  let logoutIdempotencyKey: string | undefined;
  let logoutAccessToken: string | undefined;
  let slotLogoutAllowed = false;
  let refreshPromise: Promise<AuthenticationProblem | undefined> | undefined;
  let businessRefreshKey: string | undefined;
  let businessRefreshEpoch = 0;
  let businessRetryAt = 0;
  let businessRetryProblem: AuthenticationProblem | undefined;
  let switchRefreshKey: string | undefined;
  let switchRetryAt = 0;
  let switchRetryProblem: AuthenticationProblem | undefined;
  const operationKeys = new WeakMap<IdempotentOperationHandle, string>();
  const listeners = new Set<AuthenticationListener>();
  const publish = (nextState: AuthenticationState): void => {
    state = authenticationReducer(state, { type: 'transition', state: nextState });
    for (const listener of listeners) {
      listener(state);
    }
  };
  let sessionEpoch = 0;
  const now = options.now ?? Date.now;
  let synchronizationRetryAt = 0;
  const session = createBrowserSession(
    options.realm,
    options.config.apiBaseUrl,
    options.intent,
    async (message) => {
      const epoch = ++sessionEpoch;
      if (message.event === 'session-ended') {
        accessToken = undefined;
        expiresAt = undefined;
        publish({ status: 'anonymous', transition: null });
        return;
      }
      accessToken = message.accessToken;
      expiresAt = message.expiresAt;
      if (options.intent === 'TENANT') {
        publish({ status: 'authenticated', transition: 'sessionSync' });
        await synchronizeTenantContext(epoch);
        return;
      }
      publish({ status: 'authenticated', transition: null });
    },
    () => {
      sessionEpoch += 1;
      accessToken = undefined;
      expiresAt = undefined;
      publish({ status: 'logoutPending', transition: null });
    },
    () => {
      sessionEpoch += 1;
      accessToken = undefined;
      expiresAt = undefined;
      if (state.status === 'authenticated')
        publish({
          status: 'authenticated',
          transition: 'sessionSync',
          synchronizationProblem: { code: 'SESSION_CHANGED' },
        });
      else if (state.status !== 'logoutPending') publish({ status: 'anonymous', transition: null });
    },
    now,
  );
  if (session.isLogoutPending()) state = { status: 'logoutPending', transition: null };
  let recoveryAttempted = false;
  let recoveryPending = false;
  let recoveryKey: string | undefined;
  let recoveryRetryAt = 0;
  let recoveryRetryProblem: AuthenticationProblem | undefined;
  const createIdempotencyKey = options.createIdempotencyKey ?? (() => createUuidV7(now()));
  const generatedFetch: AuthenticationFetch = async (input, init) => {
    const response = await options.fetch(input, sanitizeBrowserRequest(init));
    if (response.ok && response.status !== 204) {
      const contentType = response.headers
        .get('Content-Type')
        ?.split(';', 1)[0]
        ?.trim()
        .toLowerCase();
      if (contentType !== 'application/json') {
        return new Response(null, { status: 502 });
      }
    }
    return response;
  };
  const authenticationApi = new AuthenticationApi(
    new Configuration({
      basePath: options.config.apiBaseUrl,
      credentials: 'include',
      accessToken: () => accessToken ?? '',
      fetchApi: generatedFetch,
    }),
  );
  const oauthClientsApi = new OAuthClientsApi(
    new Configuration({
      basePath: options.config.apiBaseUrl,
      credentials: 'include',
      accessToken: () => accessToken ?? '',
      fetchApi: generatedFetch,
    }),
  );

  async function synchronizeTenantContext(
    epoch: number,
    signal?: AbortSignal,
  ): Promise<AuthenticationOperationResult> {
    let problem: AuthenticationProblem;
    try {
      const context = parseTenantAuthenticationContext(
        await authenticationApi.getCurrentTenantContext({ signal }),
      );
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      if (context !== undefined) {
        publish(authenticatedTransitionState(null, context));
        synchronizationRetryAt = 0;
        return { ok: true, state };
      }
      problem = { code: 'INVALID_SERVICE_RESPONSE' };
    } catch (error) {
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      problem = await normalizeOperationError(error);
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      if (
        error instanceof ResponseError &&
        (error.response.status === 401 || error.response.status === 403)
      ) {
        publish({ status: 'anonymous', transition: null });
        // 广播接收不持锁；先完成接收再排队发布失效，避免等待自己的 receipt。
        void invalidateRejectedToken(accessToken);
        return { ok: false, problem };
      }
    }
    synchronizationRetryAt = now() + (problem.retryAfterSeconds ?? 0) * 1_000;
    publish({
      status: 'authenticated',
      transition: 'sessionSync',
      synchronizationProblem: problem,
    });
    return { ok: false, problem };
  }

  async function refreshForBusinessRequest(
    signal?: AbortSignal,
  ): Promise<AuthenticationProblem | undefined> {
    if (refreshPromise !== undefined) {
      return refreshPromise;
    }
    if (
      state.status !== 'authenticated' ||
      (state.transition !== null && state.transition !== 'refresh')
    ) {
      return { code: 'INVALID_AUTHENTICATION_TRANSITION' };
    }
    const currentTenantContext = state.tenantContext;
    if (businessRefreshEpoch !== sessionEpoch) {
      businessRefreshEpoch = sessionEpoch;
      businessRefreshKey = undefined;
      businessRetryAt = 0;
      businessRetryProblem = undefined;
    }
    if (now() < businessRetryAt && businessRetryProblem !== undefined) {
      return {
        ...businessRetryProblem,
        retryAfterSeconds: Math.ceil((businessRetryAt - now()) / 1_000),
      };
    }
    publish(authenticatedTransitionState('refresh', currentTenantContext));
    refreshPromise = session.run(async (changed) => {
      if (
        changed &&
        state.status === 'authenticated' &&
        accessToken !== undefined &&
        expiresAt !== undefined &&
        expiresAt > now()
      ) {
        return undefined;
      }
      const epoch = sessionEpoch;
      const idempotencyKey = (businessRefreshKey ??= createIdempotencyKey());
      if (!UUID_V7.test(idempotencyKey)) {
        publish(authenticatedTransitionState(null, currentTenantContext));
        return { code: 'INVALID_IDEMPOTENCY_KEY' };
      }
      let response: unknown;
      try {
        response = await authenticationApi.refreshAccessToken(
          {
            idempotencyKey,
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            sessionSlotRequest: { sessionSlot: options.intent },
          },
          { signal },
        );
      } catch (error) {
        if (session.isLogoutPending() || epoch !== sessionEpoch) return { code: 'SESSION_ENDED' };
        if (
          error instanceof ResponseError &&
          (error.response.status === 401 || error.response.status === 403)
        ) {
          accessToken = undefined;
          expiresAt = undefined;
          publish({ status: 'anonymous', transition: null });
          sessionEpoch += 1;
          session.ended();
          businessRefreshKey = undefined;
          return { code: 'SESSION_ENDED' };
        }
        publish(authenticatedTransitionState(null, currentTenantContext));
        businessRetryProblem = await normalizeOperationError(error);
        businessRetryAt = now() + (businessRetryProblem.retryAfterSeconds ?? 0) * 1_000;
        return businessRetryProblem;
      }
      if (session.isLogoutPending() || epoch !== sessionEpoch) return { code: 'SESSION_ENDED' };
      const parsed = parseAuthenticationResponse(response, options.intent);
      if (parsed?.status !== 'authenticated') {
        publish(authenticatedTransitionState(null, currentTenantContext));
        return { code: 'INVALID_SERVICE_RESPONSE' };
      }
      accessToken = parsed.accessToken;
      expiresAt = now() + parsed.expiresIn * 1_000;
      publish(toAuthenticatedState(parsed));
      session.authenticated(accessToken, expiresAt);
      businessRefreshKey = undefined;
      businessRetryAt = 0;
      return undefined;
    });
    try {
      return await refreshPromise;
    } finally {
      refreshPromise = undefined;
    }
  }

  async function executeMutation<T>(
    operationHandle: IdempotentOperationHandle | undefined,
    signal: AbortSignal | undefined,
    execute: (idempotencyKey: string) => Promise<T>,
  ): Promise<IdempotentConsoleApiResult<T>> {
    const handle = operationHandle ?? (Object.freeze({}) as IdempotentOperationHandle);
    let idempotencyKey = operationKeys.get(handle);
    if (operationHandle !== undefined && idempotencyKey === undefined) {
      return {
        ok: false,
        problem: { code: 'INVALID_OPERATION_HANDLE' },
        operationHandle: handle,
      };
    }
    if (idempotencyKey === undefined) {
      idempotencyKey = createIdempotencyKey();
      if (!UUID_V7.test(idempotencyKey)) {
        return {
          ok: false,
          problem: { code: 'INVALID_IDEMPOTENCY_KEY' },
          operationHandle: handle,
        };
      }
      operationKeys.set(handle, idempotencyKey);
    }
    if (
      session.isLogoutPending() ||
      state.status !== 'authenticated' ||
      (state.transition !== null && state.transition !== 'refresh') ||
      accessToken === undefined ||
      expiresAt === undefined
    ) {
      return {
        ok: false,
        problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
        operationHandle: handle,
      };
    }
    if (expiresAt - now() <= 30_000) {
      const problem = await refreshForBusinessRequest(signal);
      if (problem !== undefined) {
        return { ok: false, problem, operationHandle: handle };
      }
    }
    let requestEpoch = sessionEpoch;
    try {
      const value = await execute(idempotencyKey);
      if (requestEpoch !== sessionEpoch)
        return { ok: false, problem: { code: 'SESSION_CHANGED' }, operationHandle: handle };
      operationKeys.delete(handle);
      return { ok: true, value };
    } catch (error) {
      if (requestEpoch !== sessionEpoch)
        return { ok: false, problem: { code: 'SESSION_CHANGED' }, operationHandle: handle };
      if (error instanceof ResponseError && error.response.status === 401) {
        const problem = await refreshForBusinessRequest(signal);
        if (problem !== undefined) {
          return { ok: false, problem, operationHandle: handle };
        }
        const replayToken = accessToken;
        requestEpoch = sessionEpoch;
        try {
          const value = await execute(idempotencyKey);
          if (requestEpoch !== sessionEpoch)
            return { ok: false, problem: { code: 'SESSION_CHANGED' }, operationHandle: handle };
          operationKeys.delete(handle);
          return { ok: true, value };
        } catch (replayError) {
          if (requestEpoch !== sessionEpoch)
            return { ok: false, problem: { code: 'SESSION_CHANGED' }, operationHandle: handle };
          if (replayError instanceof ResponseError && replayError.response.status === 401) {
            await invalidateRejectedToken(replayToken);
            return {
              ok: false,
              problem: { code: 'SESSION_ENDED' },
              operationHandle: handle,
            };
          }
          return {
            ok: false,
            problem: await normalizeOperationError(replayError),
            operationHandle: handle,
          };
        }
      }
      return {
        ok: false,
        problem: await normalizeOperationError(error),
        operationHandle: handle,
      };
    }
  }

  const client: ConsoleApiClient = {
    getOAuthClient: async ({ clientId, signal }) => {
      if (
        session.isLogoutPending() ||
        state.status !== 'authenticated' ||
        (state.transition !== null && state.transition !== 'refresh') ||
        accessToken === undefined ||
        expiresAt === undefined
      ) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      if (expiresAt - now() <= 30_000) {
        const problem = await refreshForBusinessRequest(signal);
        if (problem !== undefined) {
          return { ok: false, problem };
        }
      }
      let requestEpoch = sessionEpoch;
      try {
        const value = await oauthClientsApi.getOAuthClient({ clientId }, { signal });
        if (requestEpoch !== sessionEpoch)
          return { ok: false, problem: { code: 'SESSION_CHANGED' } };
        return { ok: true, value };
      } catch (error) {
        if (requestEpoch !== sessionEpoch)
          return { ok: false, problem: { code: 'SESSION_CHANGED' } };
        if (error instanceof ResponseError && error.response.status === 401) {
          const refreshProblem = await refreshForBusinessRequest(signal);
          if (refreshProblem !== undefined) {
            return { ok: false, problem: refreshProblem };
          }
          const replayToken = accessToken;
          requestEpoch = sessionEpoch;
          try {
            const value = await oauthClientsApi.getOAuthClient({ clientId }, { signal });
            if (requestEpoch !== sessionEpoch)
              return { ok: false, problem: { code: 'SESSION_CHANGED' } };
            return { ok: true, value };
          } catch (replayError) {
            if (requestEpoch !== sessionEpoch)
              return { ok: false, problem: { code: 'SESSION_CHANGED' } };
            if (replayError instanceof ResponseError && replayError.response.status === 401) {
              await invalidateRejectedToken(replayToken);
              return { ok: false, problem: { code: 'SESSION_ENDED' } };
            }
            return { ok: false, problem: await normalizeOperationError(replayError) };
          }
        }
        return { ok: false, problem: await normalizeOperationError(error) };
      }
    },
    createOAuthClient: ({ request, operationHandle, signal }) =>
      executeMutation(operationHandle, signal, (idempotencyKey) =>
        oauthClientsApi.createOAuthClient(
          { idempotencyKey, createOAuthClientRequest: request },
          { signal },
        ),
      ),
  };

  async function invalidateRejectedToken(rejectedToken: string | undefined): Promise<void> {
    await session.run(() => {
      if (session.isLogoutPending() || accessToken !== rejectedToken) return;
      sessionEpoch += 1;
      accessToken = undefined;
      expiresAt = undefined;
      publish({ status: 'anonymous', transition: null });
      session.ended();
    });
  }

  const runtime: AuthenticationRuntime = {
    intent: options.intent,
    client,
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    recover: async (signal) => {
      const epoch = sessionEpoch;
      if (recoveryAttempted) {
        return { ok: false, problem: { code: 'RECOVERY_ALREADY_ATTEMPTED' } };
      }
      if (state.status !== 'anonymous' || state.transition !== null) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      recoveryAttempted = true;
      publish({ ...state, transition: 'recover' });
      const idempotencyKey = (recoveryKey ??= createIdempotencyKey());
      if (!UUID_V7.test(idempotencyKey)) {
        publish({ status: 'anonymous', transition: null });
        return { ok: false, problem: { code: 'INVALID_IDEMPOTENCY_KEY' } };
      }
      let response: unknown;
      try {
        response = await authenticationApi.refreshAccessToken(
          {
            idempotencyKey,
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            sessionSlotRequest: { sessionSlot: options.intent },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        publish({ status: 'anonymous', transition: null });
        if (
          error instanceof ResponseError &&
          (error.response.status === 401 || error.response.status === 403)
        ) {
          accessToken = undefined;
          expiresAt = undefined;
          recoveryPending = false;
          recoveryKey = undefined;
          session.ended();
          return { ok: true, state };
        }
        recoveryPending =
          error instanceof FetchError ||
          (error instanceof ResponseError &&
            (error.response.status === 409 || error.response.status === 503));
        recoveryRetryProblem = await normalizeOperationError(error);
        recoveryRetryAt = now() + (recoveryRetryProblem.retryAfterSeconds ?? 0) * 1_000;
        return { ok: false, problem: recoveryRetryProblem };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      const authenticatedState = parseAuthenticationResponse(response, options.intent);
      if (authenticatedState === undefined) {
        publish({ status: 'anonymous', transition: null });
        return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
      }
      if (authenticatedState.status === 'authenticated') {
        accessToken = authenticatedState.accessToken;
        expiresAt = now() + authenticatedState.expiresIn * 1_000;
        publish(toAuthenticatedState(authenticatedState));
      } else {
        publish(authenticatedState.state);
      }
      recoveryPending = false;
      recoveryKey = undefined;
      return { ok: true, state };
    },
    retryRecovery: async (signal) => {
      if (
        state.status === 'authenticated' &&
        state.transition === 'sessionSync' &&
        accessToken === undefined &&
        !session.isLogoutPending()
      ) {
        publish({ status: 'anonymous', transition: null });
        recoveryAttempted = false;
        recoveryKey = undefined;
        return runtime.recover(signal);
      }
      if (
        state.status === 'authenticated' &&
        state.transition === 'sessionSync' &&
        state.synchronizationProblem !== undefined
      ) {
        if (now() < synchronizationRetryAt)
          return {
            ok: false,
            problem: {
              ...state.synchronizationProblem,
              retryAfterSeconds: Math.ceil((synchronizationRetryAt - now()) / 1_000),
            },
          };
        publish({ status: 'authenticated', transition: 'sessionSync' });
        return synchronizeTenantContext(sessionEpoch, signal);
      }
      if (!recoveryPending || state.status !== 'anonymous' || state.transition !== null) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      if (now() < recoveryRetryAt && recoveryRetryProblem !== undefined) {
        return {
          ok: false,
          problem: {
            ...recoveryRetryProblem,
            retryAfterSeconds: Math.ceil((recoveryRetryAt - now()) / 1_000),
          },
        };
      }
      recoveryPending = false;
      recoveryAttempted = false;
      return runtime.recover(signal);
    },
    login: async ({ email, password, signal }) => {
      const epoch = sessionEpoch;
      if (state.status !== 'anonymous' || state.transition !== null) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      publish({ ...state, transition: 'login' });
      let response: unknown;
      try {
        response = await authenticationApi.login(
          {
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            loginRequest: { email, password, contextType: options.intent },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        publish({ status: 'anonymous', transition: null });
        const problem = await normalizeOperationError(error);
        slotLogoutAllowed = problem.code === 'SESSION_SLOT_ALREADY_ACTIVE';
        return { ok: false, problem };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      const authenticatedState = parseAuthenticationResponse(response, options.intent);
      if (authenticatedState === undefined) {
        publish({ status: 'anonymous', transition: null });
        return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
      }
      if (authenticatedState.status === 'authenticated') {
        slotLogoutAllowed = false;
        accessToken = authenticatedState.accessToken;
        expiresAt = now() + authenticatedState.expiresIn * 1_000;
        publish(toAuthenticatedState(authenticatedState));
      } else {
        publish(authenticatedState.state);
      }
      void accessToken;
      void expiresAt;
      return { ok: true, state };
    },
    changeInitialPassword: async ({ newPassword, signal }) => {
      if (
        options.intent !== 'PLATFORM' ||
        state.status !== 'passwordChangeRequired' ||
        state.transition !== null
      ) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      publish({ ...state, transition: 'passwordChange' });
      try {
        await authenticationApi.changeInitialPassword(
          {
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            hostSfPlatformRefresh: '',
            passwordChangeRequest: { newPassword },
          },
          { signal },
        );
      } catch (error) {
        publish({ status: 'passwordChangeRequired', transition: null });
        return { ok: false, problem: await normalizeOperationError(error) };
      }
      publish({ status: 'anonymous', transition: null });
      return { ok: true, state };
    },
    selectAuthenticationContext: async ({ membershipId, signal }) => {
      const epoch = sessionEpoch;
      if (
        options.intent !== 'TENANT' ||
        state.status !== 'contextSelectionRequired' ||
        state.transition !== null ||
        !state.memberships.some((candidate) => candidate.membershipId === membershipId)
      ) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      const memberships = state.memberships;
      publish({ ...state, transition: 'contextSelection' });
      let response: unknown;
      try {
        response = await authenticationApi.selectAuthenticationContext(
          {
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            hostSfTenantRefresh: '',
            contextSelectionRequest: { membershipId },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        publish({ status: 'contextSelectionRequired', transition: null, memberships });
        return { ok: false, problem: await normalizeOperationError(error) };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      const authenticatedState = parseAuthenticationResponse(response, options.intent);
      if (authenticatedState?.status !== 'authenticated') {
        publish({ status: 'contextSelectionRequired', transition: null, memberships });
        return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
      }
      accessToken = authenticatedState.accessToken;
      expiresAt = now() + authenticatedState.expiresIn * 1_000;
      publish(toAuthenticatedState(authenticatedState));
      return { ok: true, state };
    },
    switchTenantContext: async ({ membershipId, operationHandle, signal }) => {
      let epoch = sessionEpoch;
      if (
        options.intent !== 'TENANT' ||
        state.status !== 'authenticated' ||
        state.transition !== null ||
        state.tenantContext === undefined ||
        accessToken === undefined ||
        !UUID_V7.test(membershipId)
      ) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      if (state.tenantContext.membershipId === membershipId) {
        return { ok: true, state };
      }
      const handle = operationHandle ?? (Object.freeze({}) as IdempotentOperationHandle);
      let switchKey = operationKeys.get(handle);
      if (operationHandle !== undefined && switchKey === undefined) {
        return {
          ok: false,
          problem: { code: 'INVALID_OPERATION_HANDLE' },
          operationHandle: handle,
        };
      }
      if (switchKey === undefined) {
        switchKey = createIdempotencyKey();
        if (!UUID_V7.test(switchKey)) {
          return {
            ok: false,
            problem: { code: 'INVALID_IDEMPOTENCY_KEY' },
            operationHandle: handle,
          };
        }
        operationKeys.set(handle, switchKey);
      }
      const previousTenantContext = state.tenantContext;
      publish(authenticatedTransitionState('tenantSwitch', previousTenantContext));
      try {
        await authenticationApi.switchTenantContext(
          {
            idempotencyKey: switchKey,
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            hostSfTenantRefresh: '',
            tenantSwitchRequest: { membershipId },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        publish(authenticatedTransitionState(null, previousTenantContext));
        return {
          ok: false,
          problem: await normalizeOperationError(error),
          operationHandle: handle,
        };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      operationKeys.delete(handle);
      session.changed();
      epoch = ++sessionEpoch;
      accessToken = undefined;
      expiresAt = undefined;
      publish({ status: 'authenticated', transition: 'tenantSwitchRefresh' });
      const refreshKey = (switchRefreshKey = createIdempotencyKey());
      if (!UUID_V7.test(refreshKey)) {
        return { ok: false, problem: { code: 'INVALID_IDEMPOTENCY_KEY' } };
      }
      let response: unknown;
      try {
        response = await authenticationApi.refreshAccessToken(
          {
            idempotencyKey: refreshKey,
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            sessionSlotRequest: { sessionSlot: 'TENANT' },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        if (
          error instanceof ResponseError &&
          (error.response.status === 401 || error.response.status === 403)
        ) {
          publish({ status: 'anonymous', transition: null });
          sessionEpoch += 1;
          session.ended();
        }
        switchRetryProblem = await normalizeOperationError(error);
        switchRetryAt = now() + (switchRetryProblem.retryAfterSeconds ?? 0) * 1_000;
        return { ok: false, problem: switchRetryProblem };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      const parsed = parseAuthenticationResponse(response, options.intent);
      if (parsed?.status !== 'authenticated') {
        return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
      }
      accessToken = parsed.accessToken;
      expiresAt = now() + parsed.expiresIn * 1_000;
      publish(toAuthenticatedState(parsed));
      return { ok: true, state };
    },
    retryTenantSwitchRefresh: async (signal) => {
      const epoch = sessionEpoch;
      if (
        options.intent !== 'TENANT' ||
        state.status !== 'authenticated' ||
        state.transition !== 'tenantSwitchRefresh' ||
        accessToken !== undefined
      ) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      if (now() < switchRetryAt && switchRetryProblem !== undefined) {
        return {
          ok: false,
          problem: {
            ...switchRetryProblem,
            retryAfterSeconds: Math.ceil((switchRetryAt - now()) / 1_000),
          },
        };
      }
      const refreshKey = (switchRefreshKey ??= createIdempotencyKey());
      if (!UUID_V7.test(refreshKey)) {
        return { ok: false, problem: { code: 'INVALID_IDEMPOTENCY_KEY' } };
      }
      let response: unknown;
      try {
        response = await authenticationApi.refreshAccessToken(
          {
            idempotencyKey: refreshKey,
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            sessionSlotRequest: { sessionSlot: 'TENANT' },
          },
          { signal },
        );
      } catch (error) {
        if (epoch !== sessionEpoch || session.isLogoutPending())
          return { ok: false, problem: { code: 'SESSION_ENDED' } };
        if (
          error instanceof ResponseError &&
          (error.response.status === 401 || error.response.status === 403)
        ) {
          publish({ status: 'anonymous', transition: null });
          sessionEpoch += 1;
          session.ended();
        }
        switchRetryProblem = await normalizeOperationError(error);
        switchRetryAt = now() + (switchRetryProblem.retryAfterSeconds ?? 0) * 1_000;
        return { ok: false, problem: switchRetryProblem };
      }
      if (epoch !== sessionEpoch || session.isLogoutPending())
        return { ok: false, problem: { code: 'SESSION_ENDED' } };
      const parsed = parseAuthenticationResponse(response, options.intent);
      if (parsed?.status !== 'authenticated') {
        return { ok: false, problem: { code: 'INVALID_SERVICE_RESPONSE' } };
      }
      accessToken = parsed.accessToken;
      expiresAt = now() + parsed.expiresIn * 1_000;
      publish(toAuthenticatedState(parsed));
      return { ok: true, state };
    },
    logout: async (signal) => {
      if ((state.status === 'anonymous' && !slotLogoutAllowed) || state.transition !== null) {
        return { ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } };
      }
      logoutIdempotencyKey ??= createIdempotencyKey();
      if (!UUID_V7.test(logoutIdempotencyKey)) {
        return { ok: false, problem: { code: 'INVALID_IDEMPOTENCY_KEY' } };
      }
      publish({ ...state, transition: 'logout' });
      try {
        await authenticationApi.logout(
          {
            xSFCSRF: '1',
            origin: options.config.apiBaseUrl,
            sessionSlotRequest: { sessionSlot: options.intent },
            idempotencyKey: logoutIdempotencyKey,
          },
          ({ init }) => {
            const headers = new Headers(init.headers);
            if (logoutAccessToken !== undefined)
              headers.set('Authorization', `Bearer ${logoutAccessToken}`);
            return Promise.resolve({ ...init, signal, headers });
          },
        );
      } catch (error) {
        logoutAccessToken = undefined;
        accessToken = undefined;
        expiresAt = undefined;
        publish({ status: 'logoutPending', transition: null });
        return { ok: false, problem: await normalizeOperationError(error) };
      }
      logoutAccessToken = undefined;
      accessToken = undefined;
      expiresAt = undefined;
      logoutIdempotencyKey = undefined;
      slotLogoutAllowed = false;
      publish({ status: 'anonymous', transition: null });
      return { ok: true, state };
    },
  };
  const recover = runtime.recover.bind(runtime);
  runtime.recover = (signal) => {
    if (session.isLogoutPending())
      return Promise.resolve({ ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } });
    return session.run(async (changed) => {
      if (changed && state.status === 'authenticated') return { ok: true, state };
      const result = await recover(signal);
      if (result.ok && accessToken !== undefined && expiresAt !== undefined) {
        session.authenticated(accessToken, expiresAt);
      } else if (result.ok && state.status !== 'anonymous') {
        session.changed();
      }
      return result;
    });
  };
  const login = runtime.login.bind(runtime);
  runtime.login = (input) =>
    session.run(async (changed) => {
      if (changed && state.status === 'authenticated') return { ok: true, state };
      const result = await login(input);
      if (result.ok && accessToken !== undefined && expiresAt !== undefined) {
        session.authenticated(accessToken, expiresAt);
      } else if (result.ok) {
        session.changed();
      }
      return result;
    });
  const selectContext = runtime.selectAuthenticationContext.bind(runtime);
  runtime.selectAuthenticationContext = (input) =>
    session.run(async (changed) => {
      if (changed && state.status === 'authenticated') return { ok: true, state };
      const result = await selectContext(input);
      if (result.ok && accessToken !== undefined && expiresAt !== undefined)
        session.authenticated(accessToken, expiresAt);
      return result;
    });
  const logout = runtime.logout.bind(runtime);
  const switchTenant = runtime.switchTenantContext.bind(runtime);
  runtime.switchTenantContext = (input) =>
    session.run(async () => {
      const previousToken = accessToken;
      const result = await switchTenant(input);
      if (
        result.ok &&
        accessToken !== undefined &&
        expiresAt !== undefined &&
        accessToken !== previousToken
      )
        session.authenticated(accessToken, expiresAt);
      return result;
    });
  const retrySwitch = runtime.retryTenantSwitchRefresh.bind(runtime);
  runtime.retryTenantSwitchRefresh = (signal) =>
    session.run(async () => {
      const result = await retrySwitch(signal);
      if (result.ok && accessToken !== undefined && expiresAt !== undefined)
        session.authenticated(accessToken, expiresAt);
      return result;
    });
  const changePassword = runtime.changeInitialPassword.bind(runtime);
  runtime.changeInitialPassword = (input) =>
    session.run(async () => {
      const result = await changePassword(input);
      if (result.ok) session.ended();
      return result;
    });
  runtime.logout = (signal) => {
    if (
      (state.status === 'anonymous' && !slotLogoutAllowed) ||
      (state.transition !== null && state.transition !== 'refresh')
    ) {
      return Promise.resolve({ ok: false, problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' } });
    }
    logoutAccessToken = accessToken;
    session.requestLogout();
    return session.run(async (changed) => {
      if (changed && state.status === 'anonymous') return { ok: true, state };
      const result = await logout(signal);
      if (result.ok) session.ended();
      return result;
    });
  };
  const runtimesByIntent =
    realmRuntimes.get(options.realm) ?? new Map<AuthenticationIntent, AuthenticationRuntime>();
  runtimesByIntent.set(options.intent, runtime);
  realmRuntimes.set(options.realm, runtimesByIntent);
  return runtime;
}

function sanitizeBrowserRequest(init: RequestInit | undefined): RequestInit {
  const headers = new Headers(init?.headers);
  headers.delete('Origin');
  headers.delete('Sec-Fetch-Site');
  // 所有浏览器写请求都经过 Gateway 的 CSRF 形态校验，不能只覆盖认证 operation。
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(init?.method?.toUpperCase() ?? '')) {
    headers.set('X-SF-CSRF', '1');
  }
  return { ...(init ?? {}), headers: Object.fromEntries(headers.entries()) };
}

async function normalizeOperationError(error: unknown): Promise<AuthenticationProblem> {
  if (error instanceof ResponseError) {
    return normalizeProblemResponse(error.response);
  }
  if (error instanceof FetchError) {
    return {
      code: error.cause.name === 'AbortError' ? 'REQUEST_ABORTED' : 'NETWORK_UNAVAILABLE',
    };
  }
  return { code: 'INVALID_SERVICE_RESPONSE' };
}

async function normalizeProblemResponse(response: Response): Promise<AuthenticationProblem> {
  const contentType = response.headers.get('Content-Type')?.split(';', 1)[0]?.trim().toLowerCase();
  if (contentType !== 'application/problem+json') {
    return { code: 'INVALID_SERVICE_RESPONSE' };
  }

  let input: unknown;
  try {
    input = await response.json();
  } catch {
    return { code: 'INVALID_SERVICE_RESPONSE' };
  }
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return { code: 'INVALID_SERVICE_RESPONSE' };
  }
  const value = input as Record<string, unknown>;
  if (
    typeof value.type !== 'string' ||
    typeof value.title !== 'string' ||
    value.status !== response.status ||
    typeof value.status !== 'number' ||
    !isProblemCode(value.code) ||
    value.type !== `urn:saasforge:problem:${value.code.toLowerCase().replaceAll('_', '-')}` ||
    typeof value.detail !== 'string' ||
    typeof value.traceId !== 'string' ||
    !TRACE_ID.test(value.traceId)
  ) {
    return { code: 'INVALID_SERVICE_RESPONSE' };
  }

  const fieldErrors = normalizeFieldErrors(value.errors);
  if (value.errors !== undefined && fieldErrors === undefined) {
    return { code: 'INVALID_SERVICE_RESPONSE' };
  }
  const retryAfterSeconds =
    response.status === 409 || response.status === 503
      ? parseRetryAfter(response.headers.get('Retry-After'))
      : undefined;
  return {
    code: value.code,
    status: value.status,
    traceId: value.traceId,
    ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }),
    ...(fieldErrors === undefined ? {} : { fieldErrors }),
  };
}

const PROBLEM_CODE = /^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/;
const TRACE_ID = /^(?!0{32}$)[0-9a-f]{32}$/;

function isProblemCode(input: unknown): input is string {
  return typeof input === 'string' && PROBLEM_CODE.test(input);
}

function normalizeFieldErrors(input: unknown): readonly AuthenticationFieldError[] | undefined {
  if (input === undefined) {
    return undefined;
  }
  if (!Array.isArray(input) || input.length === 0) {
    return undefined;
  }
  const errors: AuthenticationFieldError[] = [];
  for (const item of input) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      return undefined;
    }
    const value = item as Record<string, unknown>;
    if (
      !hasExactKeys(value, ['pointer', 'code', 'detail']) ||
      typeof value.pointer !== 'string' ||
      (value.pointer !== '' && !value.pointer.startsWith('/')) ||
      !isProblemCode(value.code) ||
      typeof value.detail !== 'string'
    ) {
      return undefined;
    }
    errors.push({ pointer: value.pointer, code: value.code });
  }
  return errors;
}

function parseRetryAfter(value: string | null): number | undefined {
  if (value === null || !/^\d+$/.test(value)) {
    return undefined;
  }
  return Math.min(Number(value), 300);
}

type ParsedAuthenticationResponse =
  | {
      readonly status: 'authenticated';
      readonly accessToken: string;
      readonly expiresIn: number;
      readonly tenantContext?: TenantAuthenticationContext;
    }
  | {
      readonly status: 'restricted';
      readonly state:
        PasswordChangeRequiredAuthenticationState | ContextSelectionRequiredAuthenticationState;
    };

function parseAuthenticationResponse(
  input: unknown,
  intent: AuthenticationIntent,
): ParsedAuthenticationResponse | undefined {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return undefined;
  }
  const value = input as Record<string, unknown>;
  const accessTokenKeys = ['contextState', 'accessToken', 'tokenType', 'expiresIn'];
  if (
    accessTokenKeys.every((key) => key in value) &&
    Object.keys(value).every((key) => [...accessTokenKeys, 'tenantContext'].includes(key)) &&
    value.contextState === 'ACCESS_TOKEN_ISSUED' &&
    typeof value.accessToken === 'string' &&
    value.accessToken.length > 0 &&
    value.tokenType === 'Bearer' &&
    typeof value.expiresIn === 'number' &&
    Number.isSafeInteger(value.expiresIn) &&
    value.expiresIn > 0
  ) {
    const tenantContext = parseTenantAuthenticationContext(value.tenantContext);
    if (value.tenantContext !== undefined && tenantContext === undefined) {
      return undefined;
    }
    return {
      status: 'authenticated',
      accessToken: value.accessToken,
      expiresIn: value.expiresIn,
      ...(tenantContext === undefined ? {} : { tenantContext }),
    };
  }
  if (
    intent === 'PLATFORM' &&
    hasExactKeys(value, ['contextState']) &&
    value.contextState === 'PASSWORD_CHANGE_REQUIRED'
  ) {
    return {
      status: 'restricted',
      state: { status: 'passwordChangeRequired', transition: null },
    };
  }
  if (
    intent === 'TENANT' &&
    hasExactKeys(value, ['contextState', 'memberships']) &&
    value.contextState === 'CONTEXT_SELECTION_REQUIRED' &&
    Array.isArray(value.memberships) &&
    value.memberships.length >= 2 &&
    value.memberships.every(isMembershipCandidate)
  ) {
    return {
      status: 'restricted',
      state: {
        status: 'contextSelectionRequired',
        transition: null,
        memberships: value.memberships,
      },
    };
  }
  return undefined;
}

function toAuthenticatedState(
  response: Extract<ParsedAuthenticationResponse, { readonly status: 'authenticated' }>,
): AuthenticatedAuthenticationState {
  return authenticatedTransitionState(null, response.tenantContext);
}

function authenticatedTransitionState(
  transition: AuthenticationTransition | null,
  tenantContext: TenantAuthenticationContext | undefined,
): AuthenticatedAuthenticationState {
  return {
    status: 'authenticated',
    transition,
    ...(tenantContext === undefined ? {} : { tenantContext }),
  };
}

function parseTenantAuthenticationContext(input: unknown): TenantAuthenticationContext | undefined {
  if (input === undefined) {
    return undefined;
  }
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return undefined;
  }
  const value = input as Record<string, unknown>;
  const allowedKeys = [
    'membershipId',
    'tenantId',
    'tenantDisplayName',
    'accessibleMemberships',
    'brandProfile',
  ];
  const currentMembership = {
    membershipId: value.membershipId,
    tenantId: value.tenantId,
    tenantDisplayName: value.tenantDisplayName,
  };
  if (
    !allowedKeys.filter((key) => key !== 'brandProfile').every((key) => key in value) ||
    !Object.keys(value).every((key) => allowedKeys.includes(key)) ||
    !isMembershipCandidate(currentMembership) ||
    !Array.isArray(value.accessibleMemberships) ||
    value.accessibleMemberships.length === 0 ||
    !value.accessibleMemberships.every(isMembershipCandidate) ||
    !value.accessibleMemberships.some(
      (membership) => membership.membershipId === value.membershipId,
    )
  ) {
    return undefined;
  }
  const brandProfile = parseTenantBrandProfile(value.brandProfile);
  if (value.brandProfile !== undefined && brandProfile === undefined) {
    return undefined;
  }
  return {
    ...currentMembership,
    accessibleMemberships: value.accessibleMemberships,
    ...(brandProfile === undefined ? {} : { brandProfile }),
  };
}

function parseTenantBrandProfile(input: unknown): TenantBrandProfileSnapshot | undefined {
  if (input === undefined) {
    return undefined;
  }
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return undefined;
  }
  const value = input as Record<string, unknown>;
  const requiredKeys = ['displayName', 'primaryColor', 'accentColor'];
  const allowedKeys = [...requiredKeys, 'logoUrl', 'faviconUrl'];
  if (
    !requiredKeys.every((key) => key in value) ||
    !Object.keys(value).every((key) => allowedKeys.includes(key)) ||
    typeof value.displayName !== 'string' ||
    typeof value.primaryColor !== 'string' ||
    typeof value.accentColor !== 'string' ||
    (value.logoUrl !== undefined && typeof value.logoUrl !== 'string') ||
    (value.faviconUrl !== undefined && typeof value.faviconUrl !== 'string')
  ) {
    return undefined;
  }
  return {
    displayName: value.displayName,
    primaryColor: value.primaryColor,
    accentColor: value.accentColor,
    ...(value.logoUrl === undefined ? {} : { logoUrl: value.logoUrl }),
    ...(value.faviconUrl === undefined ? {} : { faviconUrl: value.faviconUrl }),
  };
}

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function createUuidV7(timestamp: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const milliseconds = Math.max(0, Math.min(Math.trunc(timestamp), 0xffffffffffff));
  bytes[0] = Math.floor(milliseconds / 0x10000000000) & 0xff;
  bytes[1] = Math.floor(milliseconds / 0x100000000) & 0xff;
  bytes[2] = Math.floor(milliseconds / 0x1000000) & 0xff;
  bytes[3] = Math.floor(milliseconds / 0x10000) & 0xff;
  bytes[4] = Math.floor(milliseconds / 0x100) & 0xff;
  bytes[5] = milliseconds & 0xff;
  bytes[6] = ((bytes.at(6) ?? 0) & 0x0f) | 0x70;
  bytes[8] = ((bytes.at(8) ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function isMembershipCandidate(input: unknown): input is MembershipCandidate {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) {
    return false;
  }
  const value = input as Record<string, unknown>;
  return (
    hasExactKeys(value, ['membershipId', 'tenantId', 'tenantDisplayName']) &&
    typeof value.membershipId === 'string' &&
    UUID_V7.test(value.membershipId) &&
    typeof value.tenantId === 'string' &&
    UUID_V7.test(value.tenantId) &&
    typeof value.tenantDisplayName === 'string' &&
    value.tenantDisplayName.trim().length > 0 &&
    value.tenantDisplayName.length <= 200
  );
}

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value);
  return keys.length === expected.length && keys.every((key) => expected.includes(key));
}
