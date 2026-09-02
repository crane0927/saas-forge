import { describe, expect, it, vi } from 'vitest';

import {
  createAuthenticationRuntimeAfterConfig,
  parseRuntimeConfig,
  type AuthenticationRuntime,
  type AuthenticationRuntimeCreationOptions,
} from '../src';

describe('createAuthenticationRuntime', () => {
  it('creates an anonymous runtime for the host-fixed intent', () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch: vi.fn(),
    });

    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: null });
  });

  it('returns one runtime for each host intent in the same page realm', () => {
    const realm = {};
    const options = {
      realm,
      intent: 'PLATFORM' as const,
      fetch: vi.fn(),
    };

    expect(createRuntime(options)).toBe(createRuntime(options));
    expect(createRuntime({ ...options, intent: 'TENANT' })).not.toBe(createRuntime(options));
  });

  it('does not create an authentication runtime after Runtime Config failure', () => {
    expect(
      createAuthenticationRuntimeAfterConfig(
        { ok: false, error: { code: 'CONFIG_INVALID' } },
        { realm: {}, intent: 'PLATFORM', fetch: vi.fn() },
      ),
    ).toEqual({ ok: false, error: { code: 'CONFIG_INVALID' } });
  });

  it('logs in with the host intent and keeps the access token out of public state', async () => {
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() =>
      Promise.resolve(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      ),
    );
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      now: () => 1_000,
    });

    await expect(
      runtime.login({ email: 'admin@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: true,
      state: { status: 'authenticated', transition: null },
    });
    expect(runtime.getState()).toEqual({ status: 'authenticated', transition: null });
    const [url, init] = fetch.mock.calls[0] ?? [];
    expect(url).toBe('https://api.example.test/api/v1/auth/login');
    expect(init).toEqual(
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({
          email: 'admin@example.test',
          password: 'secret',
          contextType: 'PLATFORM',
        }),
      }),
    );
    const headers = new Headers(init?.headers);
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(headers.get('X-SF-CSRF')).toBe('1');
    expect(headers.has('Authorization')).toBe(false);
    expect(headers.has('Origin')).toBe(false);
  });

  it('publishes reducer-driven state transitions through the public subscription', async () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch: vi.fn(() =>
        Promise.resolve(
          Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'memory-only-token',
            tokenType: 'Bearer',
            expiresIn: 120,
          }),
        ),
      ),
    });
    const states: unknown[] = [];
    const unsubscribe = runtime.subscribe((state) => states.push(state));

    await runtime.login({ email: 'admin@example.test', password: 'secret' });
    unsubscribe();
    expect(states).toEqual([
      { status: 'anonymous', transition: 'login' },
      { status: 'authenticated', transition: null },
    ]);
  });

  it('rejects a duplicate in-flight login without issuing another request', async () => {
    let resolveResponse: ((response: Response) => void) | undefined;
    const fetch = vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          resolveResponse = resolve;
        }),
    );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
    });

    const login = runtime.login({ email: 'user@example.test', password: 'secret' });
    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: 'login' });
    await expect(
      runtime.login({ email: 'user@example.test', password: 'other-secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
    });
    expect(fetch).toHaveBeenCalledOnce();

    resolveResponse?.(
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'memory-only-token',
        tokenType: 'Bearer',
        expiresIn: 120,
      }),
    );
    await login;
  });

  it.each([
    [
      'PASSWORD_CHANGE_REQUIRED',
      { contextState: 'PASSWORD_CHANGE_REQUIRED' },
      { status: 'passwordChangeRequired', transition: null },
    ],
    [
      'CONTEXT_SELECTION_REQUIRED',
      {
        contextState: 'CONTEXT_SELECTION_REQUIRED',
        memberships: [
          {
            membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
            tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
            tenantDisplayName: '北辰科技',
          },
          {
            membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
            tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6075',
            tenantDisplayName: '云帆数据',
          },
        ],
      },
      {
        status: 'contextSelectionRequired',
        transition: null,
        memberships: [
          {
            membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
            tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
            tenantDisplayName: '北辰科技',
          },
          {
            membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
            tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6075',
            tenantDisplayName: '云帆数据',
          },
        ],
      },
    ],
  ] as const)('maps %s login responses to the stable public state', async (_name, body, state) => {
    const runtime = createRuntime({
      realm: {},
      intent: body.contextState === 'PASSWORD_CHANGE_REQUIRED' ? 'PLATFORM' : 'TENANT',
      fetch: vi.fn(() => Promise.resolve(Response.json(body))),
    });

    await expect(
      runtime.login({ email: 'user@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: true,
      state,
    });
  });

  it('rejects a malformed authentication response without changing session facts', async () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch: vi.fn(() =>
        Promise.resolve(
          Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'must-not-be-kept',
            tokenType: 'Bearer',
            expiresIn: -1,
          }),
        ),
      ),
    });

    await expect(
      runtime.login({ email: 'user@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_SERVICE_RESPONSE' },
    });
    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: null });
  });

  it('rejects a successful authentication body with an unsupported media type', async () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch: vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: 'must-not-be-kept',
              tokenType: 'Bearer',
              expiresIn: 120,
            }),
            { status: 200, headers: { 'Content-Type': 'text/html' } },
          ),
        ),
      ),
    });

    await expect(
      runtime.login({ email: 'admin@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_SERVICE_RESPONSE' },
    });
    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: null });
  });

  it('normalizes network failures without exposing the original error', async () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch: vi.fn(() => Promise.reject(new Error('socket and credential detail'))),
    });

    await expect(
      runtime.login({ email: 'admin@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'NETWORK_UNAVAILABLE' },
    });
    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: null });
  });

  it('returns only normalized Problem fields and retry guidance', async () => {
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch: vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              type: 'urn:saasforge:problem:future-login-rule',
              title: 'must not escape',
              status: 409,
              code: 'FUTURE_LOGIN_RULE',
              detail: 'email and internal detail must not escape',
              traceId: '0123456789abcdef0123456789abcdef',
              errors: [{ pointer: '/email', code: 'INVALID_EMAIL', detail: 'must not escape' }],
            }),
            {
              status: 409,
              headers: {
                'Content-Type': 'application/problem+json',
                'Retry-After': '12',
              },
            },
          ),
        ),
      ),
    });

    await expect(
      runtime.login({ email: 'user@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: {
        code: 'FUTURE_LOGIN_RULE',
        status: 409,
        traceId: '0123456789abcdef0123456789abcdef',
        retryAfterSeconds: 12,
        fieldErrors: [{ pointer: '/email', code: 'INVALID_EMAIL' }],
      },
    });
  });

  it('recovers the fixed slot only once during cold start', async () => {
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(() =>
      Promise.resolve(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      ),
    );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });

    await expect(runtime.recover()).resolves.toEqual({
      ok: true,
      state: { status: 'authenticated', transition: null },
    });
    await expect(runtime.recover()).resolves.toEqual({
      ok: false,
      problem: { code: 'RECOVERY_ALREADY_ATTEMPTED' },
    });
    expect(fetch).toHaveBeenCalledOnce();
    const [, init] = fetch.mock.calls[0] ?? [];
    expect(init?.body).toBe(JSON.stringify({ sessionSlot: 'TENANT' }));
    const headers = new Headers(init?.headers);
    expect(headers.get('Idempotency-Key')).toBe('018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073');
  });

  it.each([401, 403])('treats refresh status %s as an ended selected session', async (status) => {
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch: vi.fn(() => Promise.resolve(problemResponse(status, 'SESSION_INVALID'))),
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });

    await expect(runtime.recover()).resolves.toEqual({
      ok: true,
      state: { status: 'anonymous', transition: null },
    });
  });

  it('allows only explicit manual recovery after a recoverable cold-start refresh', async () => {
    let now = 0;
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(problemResponse(503, 'REFRESH_LEASE_BUSY', '999'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'recovered-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      now: () => now,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });

    await expect(runtime.recover()).resolves.toEqual({
      ok: false,
      problem: {
        code: 'REFRESH_LEASE_BUSY',
        status: 503,
        traceId: '0123456789abcdef0123456789abcdef',
        retryAfterSeconds: 300,
      },
    });
    expect(fetch).toHaveBeenCalledOnce();
    now = 300_000;
    await expect(runtime.retryRecovery()).resolves.toEqual({
      ok: true,
      state: { status: 'authenticated', transition: null },
    });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('changes an initial password only from the Platform restricted state', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(Response.json({ contextState: 'PASSWORD_CHANGE_REQUIRED' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
    });
    await runtime.login({ email: 'admin@example.test', password: 'old-secret' });

    await expect(runtime.changeInitialPassword({ newPassword: 'new-secret' })).resolves.toEqual({
      ok: true,
      state: { status: 'anonymous', transition: null },
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    const [url, init] = fetch.mock.calls[1] ?? [];
    expect(url).toBe('https://api.example.test/api/v1/auth/password-changes');
    expect(init?.body).toBe(JSON.stringify({ newPassword: 'new-secret' }));
    await expect(runtime.changeInitialPassword({ newPassword: 'again' })).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
    });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('selects only a server-provided Tenant membership', async () => {
    const membershipId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'CONTEXT_SELECTION_REQUIRED',
          memberships: [
            {
              membershipId,
              tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
              tenantDisplayName: '北辰科技',
            },
            {
              membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
              tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6075',
              tenantDisplayName: '云帆数据',
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'selected-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
    });
    await runtime.login({ email: 'user@example.test', password: 'secret' });

    await expect(
      runtime.selectAuthenticationContext({
        membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6079',
      }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
    });
    expect(fetch).toHaveBeenCalledOnce();

    await expect(runtime.selectAuthenticationContext({ membershipId })).resolves.toEqual({
      ok: true,
      state: { status: 'authenticated', transition: null },
    });
    const [url, init] = fetch.mock.calls[1] ?? [];
    expect(url).toBe('https://api.example.test/api/v1/auth/context-selections');
    expect(init?.body).toBe(JSON.stringify({ membershipId }));
  });

  it('enters logoutPending on an unknown result and reuses the logout operation key', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockRejectedValueOnce(new Error('unknown result'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    await expect(runtime.logout()).resolves.toEqual({
      ok: false,
      problem: { code: 'NETWORK_UNAVAILABLE' },
    });
    expect(runtime.getState()).toEqual({ status: 'logoutPending', transition: null });

    await expect(runtime.logout()).resolves.toEqual({
      ok: true,
      state: { status: 'anonymous', transition: null },
    });
    const logoutCalls = fetch.mock.calls.slice(1);
    expect(logoutCalls).toHaveLength(2);
    for (const [, init] of logoutCalls) {
      const headers = new Headers(init?.headers);
      expect(headers.get('Idempotency-Key')).toBe('018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073');
    }
    expect(new Headers(logoutCalls[0]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer memory-only-token',
    );
    expect(new Headers(logoutCalls[1]?.[1]?.headers).has('Authorization')).toBe(false);
  });

  it('allows an explicit selected-slot logout after login reports an active slot', async () => {
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(problemResponse(409, 'SESSION_SLOT_ALREADY_ACTIVE'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });

    await expect(
      runtime.login({ email: 'admin@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: false,
      problem: {
        code: 'SESSION_SLOT_ALREADY_ACTIVE',
        status: 409,
        traceId: '0123456789abcdef0123456789abcdef',
      },
    });
    await expect(runtime.logout()).resolves.toEqual({
      ok: true,
      state: { status: 'anonymous', transition: null },
    });

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls[1]?.[0]).toBe('https://api.example.test/api/v1/auth/logout');
    expect(fetch.mock.calls[1]?.[1]?.body).toBe(JSON.stringify({ sessionSlot: 'PLATFORM' }));
  });

  it('refreshes at the 30 second boundary before a typed read operation', async () => {
    let currentTime = 1_000;
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 31,
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'refreshed-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(Response.json(oauthClientDetail(clientId)));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      now: () => currentTime,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });
    currentTime = 2_000;

    const result = await runtime.client.getOAuthClient({ clientId });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.clientId).toBe(clientId);
    }
    expect(fetch.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      'https://api.example.test/api/v1/auth/login',
      'https://api.example.test/api/v1/auth/refresh',
      `https://api.example.test/api/v1/platform/oauth-clients/${clientId}`,
    ]);
    expect(new Headers(fetch.mock.calls[2]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer refreshed-token',
    );
  });

  it('shares one refresh and replays a typed GET at most once after its first 401', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(problemResponse(401, 'ACCESS_TOKEN_INVALID'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'refreshed-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(Response.json(oauthClientDetail(clientId)));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    const result = await runtime.client.getOAuthClient({ clientId });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.clientId).toBe(clientId);
    }
    expect(fetch.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      'https://api.example.test/api/v1/auth/login',
      `https://api.example.test/api/v1/platform/oauth-clients/${clientId}`,
      'https://api.example.test/api/v1/auth/refresh',
      `https://api.example.test/api/v1/platform/oauth-clients/${clientId}`,
    ]);
    expect(new Headers(fetch.mock.calls[3]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer refreshed-token',
    );
  });

  it('ends the selected session when a replayed GET returns 401 again', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(problemResponse(401, 'ACCESS_TOKEN_INVALID'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'refreshed-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(problemResponse(401, 'ACCESS_TOKEN_INVALID'));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    await expect(runtime.client.getOAuthClient({ clientId })).resolves.toEqual({
      ok: false,
      problem: { code: 'SESSION_ENDED' },
    });
    expect(runtime.getState()).toEqual({ status: 'anonymous', transition: null });
  });

  it('shares one preflight refresh between concurrent typed operations', async () => {
    let currentTime = 1_000;
    let resolveRefresh: ((response: Response) => void) | undefined;
    const firstClientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const secondClientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6077';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 31,
        }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            resolveRefresh = resolve;
          }),
      )
      .mockResolvedValueOnce(Response.json(oauthClientDetail(firstClientId)))
      .mockResolvedValueOnce(Response.json(oauthClientDetail(secondClientId)));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      now: () => currentTime,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });
    currentTime = 2_000;

    const first = runtime.client.getOAuthClient({ clientId: firstClientId });
    const second = runtime.client.getOAuthClient({ clientId: secondClientId });
    await vi.waitFor(() => {
      expect(fetch).toHaveBeenCalledTimes(2);
    });
    resolveRefresh?.(
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'shared-token',
        tokenType: 'Bearer',
        expiresIn: 120,
      }),
    );

    const results = await Promise.all([first, second]);
    expect(results.every((result) => result.ok)).toBe(true);
    expect(results[0].ok && results[0].value.clientId).toBe(firstClientId);
    expect(results[1].ok && results[1].value.clientId).toBe(secondClientId);
    expect(
      fetch.mock.calls.filter(([url]) => requestUrl(url).endsWith('/api/v1/auth/refresh')),
    ).toHaveLength(1);
  });

  it('supplies the controlled browser marker for typed business mutations', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockImplementation((_input, init) => {
        if (new Headers(init?.headers).get('X-SF-CSRF') !== '1')
          return Promise.resolve(problemResponse(403, 'BROWSER_REQUEST_REJECTED'));
        return Promise.resolve(
          Response.json({ ...oauthClientDetail(clientId), clientSecret: 'returned-once' }),
        );
      });
    const runtime = createRuntime({ realm: {}, intent: 'PLATFORM', fetch });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    const result = await runtime.client.createOAuthClient({
      request: { displayName: 'Console Client', allowedScopes: new Set(['runtime:read']) },
    });

    expect(result.ok).toBe(true);
    const headers = new Headers(fetch.mock.calls[1]?.[1]?.headers);
    expect(headers.get('X-SF-CSRF')).toBe('1');
    expect(headers.has('Origin')).toBe(false);
    expect(headers.has('Sec-Fetch-Site')).toBe(false);
  });

  it('keeps an opaque mutation handle after cancellation and reuses its UUIDv7 key', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockRejectedValueOnce(new DOMException('cancelled', 'AbortError'))
      .mockResolvedValueOnce(
        Response.json({
          clientId,
          displayName: 'Runtime Client',
          allowedScopes: ['runtime:read'],
          status: 'ACTIVE',
          createdAt: '2026-09-01T00:00:00Z',
          updatedAt: '2026-09-01T00:00:00Z',
          clientSecret: 'returned-once',
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });
    const abortController = new AbortController();
    abortController.abort();

    const first = await runtime.client.createOAuthClient({
      request: { displayName: 'Runtime Client', allowedScopes: new Set(['runtime:read']) },
      signal: abortController.signal,
    });
    expect(first.ok).toBe(false);
    if (first.ok) {
      throw new Error('expected cancellation');
    }
    expect(first.problem).toEqual({ code: 'REQUEST_ABORTED' });
    expect(typeof first.operationHandle).toBe('object');
    expect(fetch.mock.calls[1]?.[1]?.signal).toBe(abortController.signal);

    const retry = await runtime.client.createOAuthClient({
      request: { displayName: 'Runtime Client', allowedScopes: new Set(['runtime:read']) },
      operationHandle: first.operationHandle,
    });
    expect(retry.ok).toBe(true);
    if (retry.ok) {
      expect(retry.value.clientId).toBe(clientId);
    }
    for (const [, init] of fetch.mock.calls.slice(1)) {
      expect(new Headers(init?.headers).get('Idempotency-Key')).toBe(
        '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
      );
    }
  });

  it('replays a rebuildable typed mutation once with the same operation key after refresh', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(problemResponse(401, 'ACCESS_TOKEN_INVALID'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'refreshed-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          clientId,
          displayName: 'Runtime Client',
          allowedScopes: ['runtime:read'],
          status: 'ACTIVE',
          createdAt: '2026-09-01T00:00:00Z',
          updatedAt: '2026-09-01T00:00:00Z',
          clientSecret: 'returned-once',
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    const result = await runtime.client.createOAuthClient({
      request: { displayName: 'Runtime Client', allowedScopes: new Set(['runtime:read']) },
    });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.clientId).toBe(clientId);
    }
    const mutationCalls = fetch.mock.calls.filter(([url]) =>
      requestUrl(url).endsWith('/api/v1/platform/oauth-clients'),
    );
    expect(mutationCalls).toHaveLength(2);
    expect(new Headers(mutationCalls[0]?.[1]?.headers).get('Idempotency-Key')).toBe(
      new Headers(mutationCalls[1]?.[1]?.headers).get('Idempotency-Key'),
    );
    expect(mutationCalls[0]?.[1]?.body).toBe(mutationCalls[1]?.[1]?.body);
  });

  it('does not refresh or end the session for an ordinary domain 403', async () => {
    const clientId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076';
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        }),
      )
      .mockResolvedValueOnce(problemResponse(403, 'FORBIDDEN'));
    const runtime = createRuntime({
      realm: {},
      intent: 'PLATFORM',
      fetch,
    });
    await runtime.login({ email: 'admin@example.test', password: 'secret' });

    await expect(runtime.client.getOAuthClient({ clientId })).resolves.toEqual({
      ok: false,
      problem: {
        code: 'FORBIDDEN',
        status: 403,
        traceId: '0123456789abcdef0123456789abcdef',
      },
    });
    expect(runtime.getState()).toEqual({ status: 'authenticated', transition: null });
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('commits a Tenant switch as 204 then refresh without reusing the old token', async () => {
    const membershipId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071';
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId,
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'old-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...currentMembership,
            accessibleMemberships: [currentMembership, targetMembership],
          },
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'new-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...targetMembership,
            accessibleMemberships: [currentMembership, targetMembership],
            brandProfile: {
              displayName: 'Target Brand',
              primaryColor: '#155EEF',
              accentColor: '#7A5AF8',
            },
          },
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'user@example.test', password: 'secret' });
    const states: unknown[] = [];
    runtime.subscribe((state) => states.push(state));

    await expect(runtime.switchTenantContext({ membershipId })).resolves.toEqual({
      ok: true,
      state: {
        status: 'authenticated',
        transition: null,
        tenantContext: {
          ...targetMembership,
          accessibleMemberships: [currentMembership, targetMembership],
          brandProfile: {
            displayName: 'Target Brand',
            primaryColor: '#155EEF',
            accentColor: '#7A5AF8',
          },
        },
      },
    });
    expect(states).toContainEqual({ status: 'authenticated', transition: 'tenantSwitchRefresh' });
    expect(fetch.mock.calls.map(([url]) => requestUrl(url))).toEqual([
      'https://api.example.test/api/v1/auth/login',
      'https://api.example.test/api/v1/auth/tenant-switches',
      'https://api.example.test/api/v1/auth/refresh',
    ]);
    expect(new Headers(fetch.mock.calls[1]?.[1]?.headers).has('Authorization')).toBe(false);
    expect(new Headers(fetch.mock.calls[2]?.[1]?.headers).has('Authorization')).toBe(false);
  });

  it('publishes the authenticated Tenant context from the authentication response', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6075',
      tenantDisplayName: 'Target Tenant',
    };
    const fetch = vi.fn(() =>
      Promise.resolve(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'tenant-memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...currentMembership,
            accessibleMemberships: [currentMembership, targetMembership],
            brandProfile: {
              displayName: 'Current Brand',
              logoUrl: '/brands/current-logo.svg',
              faviconUrl: '/brands/current-favicon.svg',
              primaryColor: '#155EEF',
              accentColor: '#7A5AF8',
            },
          },
        }),
      ),
    );
    const runtime = createRuntime({ realm: {}, intent: 'TENANT', fetch });

    await expect(
      runtime.login({ email: 'user@example.test', password: 'secret' }),
    ).resolves.toEqual({
      ok: true,
      state: {
        status: 'authenticated',
        transition: null,
        tenantContext: {
          ...currentMembership,
          accessibleMemberships: [currentMembership, targetMembership],
          brandProfile: {
            displayName: 'Current Brand',
            logoUrl: '/brands/current-logo.svg',
            faviconUrl: '/brands/current-favicon.svg',
            primaryColor: '#155EEF',
            accentColor: '#7A5AF8',
          },
        },
      },
    });
  });

  it('treats switching to the current Tenant membership as a local no-op', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const fetch = vi.fn(() =>
      Promise.resolve(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'tenant-memory-only-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...currentMembership,
            accessibleMemberships: [currentMembership],
          },
        }),
      ),
    );
    const runtime = createRuntime({ realm: {}, intent: 'TENANT', fetch });
    await runtime.login({ email: 'user@example.test', password: 'secret' });

    await expect(
      runtime.switchTenantContext({ membershipId: currentMembership.membershipId }),
    ).resolves.toEqual({
      ok: true,
      state: {
        status: 'authenticated',
        transition: null,
        tenantContext: {
          ...currentMembership,
          accessibleMemberships: [currentMembership],
        },
      },
    });
    expect(fetch).toHaveBeenCalledOnce();
  });

  it('keeps a committed Tenant switch pending across a recoverable refresh failure', async () => {
    let now = 0;
    const membershipId = '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071';
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId,
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'old-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...currentMembership,
            accessibleMemberships: [currentMembership, targetMembership],
          },
        }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(problemResponse(503, 'REFRESH_LEASE_BUSY', '999'))
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'new-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...targetMembership,
            accessibleMemberships: [currentMembership, targetMembership],
          },
        }),
      );
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
      now: () => now,
    });
    await runtime.login({ email: 'user@example.test', password: 'secret' });

    await expect(runtime.switchTenantContext({ membershipId })).resolves.toEqual({
      ok: false,
      problem: {
        code: 'REFRESH_LEASE_BUSY',
        status: 503,
        traceId: '0123456789abcdef0123456789abcdef',
        retryAfterSeconds: 300,
      },
    });
    expect(runtime.getState()).toEqual({
      status: 'authenticated',
      transition: 'tenantSwitchRefresh',
    });
    now = 300_000;
    await expect(runtime.retryTenantSwitchRefresh()).resolves.toEqual({
      ok: true,
      state: {
        status: 'authenticated',
        transition: null,
        tenantContext: {
          ...targetMembership,
          accessibleMemberships: [currentMembership, targetMembership],
        },
      },
    });
    expect(
      fetch.mock.calls.filter(([url]) => requestUrl(url).endsWith('/api/v1/auth/tenant-switches')),
    ).toHaveLength(1);
  });

  it('retries an uncommitted Tenant switch with the same operation handle and key', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    const authenticatedResponse = Response.json({
      contextState: 'ACCESS_TOKEN_ISSUED',
      accessToken: 'tenant-token',
      tokenType: 'Bearer',
      expiresIn: 120,
      tenantContext: {
        ...currentMembership,
        accessibleMemberships: [currentMembership, targetMembership],
      },
    });
    const targetResponse = Response.json({
      contextState: 'ACCESS_TOKEN_ISSUED',
      accessToken: 'target-token',
      tokenType: 'Bearer',
      expiresIn: 120,
      tenantContext: {
        ...targetMembership,
        accessibleMemberships: [currentMembership, targetMembership],
      },
    });
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(authenticatedResponse)
      .mockResolvedValueOnce(problemResponse(503, 'TENANT_CONTEXT_SWITCH_UNAVAILABLE', '3'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(targetResponse);
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });
    await runtime.login({ email: 'user@example.test', password: 'secret' });

    const first = await runtime.switchTenantContext({
      membershipId: targetMembership.membershipId,
    });
    expect(first.ok).toBe(false);
    if (first.ok || first.operationHandle === undefined) {
      throw new Error('expected a reusable Tenant switch operation handle');
    }
    const second = await runtime.switchTenantContext({
      membershipId: targetMembership.membershipId,
      operationHandle: first.operationHandle,
    });
    expect(second.ok).toBe(true);
    expect(runtime.getState()).toEqual({
      status: 'authenticated',
      transition: null,
      tenantContext: {
        ...targetMembership,
        accessibleMemberships: [currentMembership, targetMembership],
      },
    });

    const switchCalls = fetch.mock.calls.filter(([url]) =>
      requestUrl(url).endsWith('/api/v1/auth/tenant-switches'),
    );
    expect(switchCalls).toHaveLength(2);
    expect(new Headers(switchCalls[0]?.[1]?.headers).get('Idempotency-Key')).toBe(
      new Headers(switchCalls[1]?.[1]?.headers).get('Idempotency-Key'),
    );
  });

  it('blocks business requests while a Tenant switch is in flight', async () => {
    const currentMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6070',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
      tenantDisplayName: 'Current Tenant',
    };
    const targetMembership = {
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6074',
      tenantDisplayName: 'Target Tenant',
    };
    let resolveSwitch: ((response: Response) => void) | undefined;
    const fetch = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: {
            ...currentMembership,
            accessibleMemberships: [currentMembership, targetMembership],
          },
        }),
      )
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            resolveSwitch = resolve;
          }),
      )
      .mockResolvedValueOnce(
        Response.json({
          clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
          displayName: 'Must not be requested',
          allowedScopes: [],
          status: 'ACTIVE',
          createdAt: '2026-09-01T00:00:00Z',
          updatedAt: '2026-09-01T00:00:00Z',
        }),
      );
    const runtime = createRuntime({ realm: {}, intent: 'TENANT', fetch });
    await runtime.login({ email: 'user@example.test', password: 'secret' });

    const switching = runtime.switchTenantContext({ membershipId: targetMembership.membershipId });
    await expect(
      runtime.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' }),
    ).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
    });
    expect(fetch).toHaveBeenCalledTimes(2);
    resolveSwitch?.(problemResponse(503, 'TENANT_CONTEXT_SWITCH_UNAVAILABLE', '3'));
    await switching;
  });

  it('blocks cold-start refresh when the host restores logoutPending', async () => {
    const fetch = vi.fn(() => Promise.resolve(new Response(null, { status: 204 })));
    const runtime = createRuntime({
      realm: {},
      intent: 'TENANT',
      fetch,
      initialLogoutPending: true,
      createIdempotencyKey: () => '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6073',
    });

    expect(runtime.getState()).toEqual({ status: 'logoutPending', transition: null });
    await expect(runtime.recover()).resolves.toEqual({
      ok: false,
      problem: { code: 'INVALID_AUTHENTICATION_TRANSITION' },
    });
    expect(fetch).not.toHaveBeenCalled();
    await expect(runtime.logout()).resolves.toEqual({
      ok: true,
      state: { status: 'anonymous', transition: null },
    });
    expect(fetch).toHaveBeenCalledOnce();
  });
});

function problemResponse(status: number, code: string, retryAfter?: string): Response {
  return new Response(
    JSON.stringify({
      type: `urn:saasforge:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: 'not exposed',
      status,
      code,
      detail: 'not exposed',
      traceId: '0123456789abcdef0123456789abcdef',
    }),
    {
      status,
      headers: {
        'Content-Type': 'application/problem+json',
        ...(retryAfter === undefined ? {} : { 'Retry-After': retryAfter }),
      },
    },
  );
}

function oauthClientDetail(clientId: string) {
  return {
    clientId,
    displayName: 'Console Client',
    clientType: 'RUNTIME_SERVICE',
    reservedServiceKey: null,
    allowedScopes: ['runtime:read'],
    status: 'ACTIVE',
    createdAt: '2026-09-01T00:00:00Z',
    updatedAt: '2026-09-01T00:00:00Z',
    revokedAt: null,
  };
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') {
    return input;
  }
  return input instanceof URL ? input.href : input.url;
}

function createRuntime(options: AuthenticationRuntimeCreationOptions): AuthenticationRuntime {
  const result = createAuthenticationRuntimeAfterConfig(
    parseRuntimeConfig({ schemaVersion: 1, apiBaseUrl: 'https://api.example.test' }),
    options,
  );
  if (!result.ok) {
    throw new Error('test Runtime Config must be valid');
  }
  return result.runtime;
}
