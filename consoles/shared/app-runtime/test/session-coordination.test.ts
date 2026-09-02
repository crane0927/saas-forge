import { describe, expect, it } from 'vitest';
import {
  createAuthenticationRuntimeAfterConfig,
  parseRuntimeConfig,
  type AuthenticationIntent,
} from '../src';

describe('same-Origin session coordination through AuthenticationRuntime', () => {
  it('does not restore synchronization UI from a late error body after session end', async () => {
    const origin = browserOrigin();
    let body!: ReadableStreamDefaultController<Uint8Array>;
    let reading = false;
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve(
        requestUrl(input).endsWith('/context')
          ? new Response(
              new ReadableStream<Uint8Array>(
                {
                  start(controller) {
                    body = controller;
                  },
                  pull() {
                    reading = true;
                  },
                },
                { highWaterMark: 0 },
              ),
              { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
            )
          : Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: 'tenant',
              tokenType: 'Bearer',
              expiresIn: 120,
              tenantContext: tenantContext(),
            }),
      );
    const first = runtime(origin.realm(), fetch, undefined, 'TENANT');
    const second = runtime(origin.realm(), fetch, undefined, 'TENANT');
    await first.recover();
    await expect.poll(() => reading).toBe(true);
    origin.deliver({ event: 'session-ended', contextType: 'TENANT', generation: 10 });
    body.enqueue(
      new TextEncoder().encode(
        JSON.stringify({
          type: 'urn:saasforge:problem:revocation-unavailable',
          title: 'Unavailable',
          detail: 'private',
          status: 503,
          code: 'REVOCATION_UNAVAILABLE',
          traceId: '0123456789abcdef0123456789abcdef',
        }),
      ),
    );
    body.close();
    // 让已经到达的 HTTP body 完成微任务处理，而不是等待或扩大网络超时。
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(second.getState()).toEqual({ status: 'anonymous', transition: null });
  });
  it('retires an uncertain refresh key when a peer establishes a newer session', async () => {
    const origin = browserOrigin();
    let now = 0;
    const keys: (string | null)[] = [];
    const fetch = (input: RequestInfo | URL, init?: RequestInit) => {
      if (!requestUrl(input).endsWith('/refresh'))
        return Promise.resolve(
          Response.json({
            clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
            allowedScopes: [],
            createdAt: '2026-09-01T00:00:00Z',
            updatedAt: '2026-09-01T00:00:00Z',
          }),
        );
      keys.push(new Headers(init?.headers).get('Idempotency-Key'));
      return Promise.resolve(
        keys.length === 2
          ? problem(503, 'REFRESH_LEASE_BUSY', '300')
          : Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: `token-${String(keys.length)}`,
              tokenType: 'Bearer',
              expiresIn: 120,
            }),
      );
    };
    const first = runtime(origin.realm(), fetch, () => now);
    const second = runtime(origin.realm(), fetch, () => now);
    await Promise.all([first.recover(), second.recover()]);
    now = 100_000;
    const read = (tab: typeof first) =>
      tab.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
    expect((await read(first)).ok).toBe(false);
    expect((await read(second)).ok).toBe(true);
    now = 200_000;
    expect((await read(first)).ok).toBe(true);
    expect(keys).toHaveLength(4);
    expect(keys[3]).not.toBe(keys[1]);
  });
  it('does not deliver an old protected read after the same tab commits a Tenant switch', async () => {
    let finishRead!: (response: Response) => void;
    const tab = runtime(
      {},
      (input) => {
        const url = requestUrl(input);
        if (url.includes('/oauth-clients/'))
          return new Promise<Response>((resolve) => {
            finishRead = resolve;
          });
        if (url.endsWith('/tenant-switches'))
          return Promise.resolve(new Response(null, { status: 204 }));
        return Promise.resolve(
          Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'tenant-token',
            tokenType: 'Bearer',
            expiresIn: 120,
            tenantContext: tenantContext(),
          }),
        );
      },
      undefined,
      'TENANT',
    );
    await tab.recover();
    const read = tab.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
    await expect.poll(() => typeof finishRead).toBe('function');
    await tab.switchTenantContext({ membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6079' });
    finishRead(
      Response.json({
        clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
        allowedScopes: [],
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
      }),
    );
    expect(await read).toEqual({ ok: false, problem: { code: 'SESSION_CHANGED' } });
  });
  it('lets a peer recover after a committed switch has no success broadcast', async () => {
    const origin = browserOrigin();
    let refreshes = 0;
    const fetch = (input: RequestInfo | URL) => {
      const url = requestUrl(input);
      if (url.endsWith('/context')) return Promise.resolve(Response.json(tenantContext()));
      if (url.endsWith('/tenant-switches'))
        return Promise.resolve(new Response(null, { status: 204 }));
      refreshes += 1;
      return Promise.resolve(
        refreshes === 2
          ? problem(503, 'REFRESH_LEASE_BUSY', '0')
          : Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: `tenant-${String(refreshes)}`,
              tokenType: 'Bearer',
              expiresIn: 120,
              tenantContext: tenantContext(),
            }),
      );
    };
    const first = runtime(origin.realm(), fetch, undefined, 'TENANT');
    const second = runtime(origin.realm(), fetch, undefined, 'TENANT');
    await Promise.all([first.recover(), second.recover()]);
    await first.switchTenantContext({ membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6079' });
    expect((await second.retryRecovery()).ok).toBe(true);
    expect(second.getState().transition).toBeNull();
    expect(refreshes).toBe(3);
  });
  it('does not accept expired, duplicate, reordered, foreign-slot, or non-whitelisted broadcasts', async () => {
    const origin = browserOrigin();
    const headers: (string | null)[] = [];
    const tab = runtime(
      origin.realm(),
      (_input, init) => {
        headers.push(new Headers(init?.headers).get('Authorization'));
        return Promise.resolve(
          Response.json({
            clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
            allowedScopes: [],
            createdAt: '2026-09-01T00:00:00Z',
            updatedAt: '2026-09-01T00:00:00Z',
          }),
        );
      },
      () => 1_000,
    );
    const valid = {
      event: 'refresh-succeeded',
      contextType: 'PLATFORM',
      generation: 2,
      accessToken: 'current',
      expiresAt: 120_000,
    };
    origin.deliver({ ...valid, generation: 1, accessToken: 'expired', expiresAt: 500 });
    expect(tab.getState().status).toBe('anonymous');
    origin.deliver(valid);
    origin.deliver({ ...valid, accessToken: 'duplicate' });
    origin.deliver({ ...valid, generation: 1, accessToken: 'old' });
    origin.deliver({ ...valid, generation: 3, contextType: 'TENANT', accessToken: 'foreign' });
    origin.deliver({ ...valid, generation: 4, detail: 'private detail', accessToken: 'forbidden' });
    await tab.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
    expect(headers).toEqual(['Bearer current']);
    origin.deliver({ event: 'session-ended', contextType: 'PLATFORM', generation: 5 });
    origin.deliver({ ...valid, generation: 4 });
    expect(tab.getState().status).toBe('anonymous');
  });
  it('recovers a failed Tenant snapshot read without rotating the shared Cookie', async () => {
    const origin = browserOrigin();
    let now = 0;
    let reads = 0;
    let refreshes = 0;
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        if (requestUrl(input).endsWith('/context')) {
          reads += 1;
          return reads === 1
            ? problem(503, 'TOKEN_REVOCATION_STATUS_UNAVAILABLE', '2')
            : Response.json(tenantContext());
        }
        refreshes += 1;
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'shared-tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: tenantContext(),
        });
      });
    const first = runtime(origin.realm(), fetch, () => now, 'TENANT');
    const second = runtime(origin.realm(), fetch, () => now, 'TENANT');
    await first.recover();
    await expect
      .poll(() => second.getState())
      .toMatchObject({
        status: 'authenticated',
        transition: 'sessionSync',
        synchronizationProblem: { code: 'TOKEN_REVOCATION_STATUS_UNAVAILABLE' },
      });
    expect((await second.retryRecovery()).ok).toBe(false);
    expect(reads).toBe(1);
    now = 2_000;
    expect((await second.retryRecovery()).ok).toBe(true);
    expect(second.getState()).toMatchObject({
      status: 'authenticated',
      transition: null,
      tenantContext: tenantContext(),
    });
    expect(refreshes).toBe(1);
  });
  it.each(['business', 'switch'] as const)(
    'keeps the %s refresh key through bounded temporary-failure recovery',
    async (operation) => {
      let now = 0;
      const keys: (string | null)[] = [];
      const tab = runtime(
        {},
        (input, init) =>
          Promise.resolve().then(() => {
            if (requestUrl(input).endsWith('/tenant-switches'))
              return new Response(null, { status: 204 });
            if (!requestUrl(input).endsWith('/refresh'))
              return Response.json({
                clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
                displayName: 'Client',
                clientType: 'RUNTIME_SERVICE',
                allowedScopes: [],
                status: 'ACTIVE',
                createdAt: '2026-09-01T00:00:00Z',
                updatedAt: '2026-09-01T00:00:00Z',
              });
            keys.push(new Headers(init?.headers).get('Idempotency-Key'));
            if (keys.length === 2) return problem(503, 'TOKEN_REVOCATION_STATUS_UNAVAILABLE', '2');
            return Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: `token-${String(keys.length)}`,
              tokenType: 'Bearer',
              expiresIn: 120,
              tenantContext: tenantContext(),
            });
          }),
        () => now,
        'TENANT',
      );
      await tab.recover();
      now = 100_000;
      const attempt = () =>
        operation === 'business'
          ? tab.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' })
          : tab.retryTenantSwitchRefresh();
      const failed =
        operation === 'business'
          ? await attempt()
          : await tab.switchTenantContext({ membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6079' });
      expect(failed.ok).toBe(false);
      expect((await attempt()).ok).toBe(false);
      expect(keys).toHaveLength(2);
      now = 102_000;
      expect((await attempt()).ok).toBe(true);
      expect(keys).toHaveLength(3);
      expect(keys[2]).toBe(keys[1]);
    },
  );
  it.each(['preflight', 'replay', 'switch-refresh'])(
    'propagates definitive authorization loss from %s to the other tab',
    async (operation) => {
      const origin = browserOrigin();
      const intent = operation === 'switch-refresh' ? 'TENANT' : 'PLATFORM';
      let now = 0;
      let refreshes = 0;
      const context = tenantContext();
      const fetch = (input: RequestInfo | URL) =>
        Promise.resolve().then(() => {
          const url = requestUrl(input);
          if (url.endsWith('/context')) return Response.json(context);
          if (url.endsWith('/tenant-switches')) return new Response(null, { status: 204 });
          if (!url.endsWith('/refresh')) return problem(401, 'ACCESS_TOKEN_INVALID', '0');
          if (++refreshes > 1 && operation !== 'replay')
            return problem(401, 'SESSION_INVALID', '0');
          return Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: `token-${String(refreshes)}`,
            tokenType: 'Bearer',
            expiresIn: 120,
            ...(intent === 'TENANT' ? { tenantContext: context } : {}),
          });
        });
      const first = runtime(origin.realm(), fetch, () => now, intent);
      const second = runtime(origin.realm(), fetch, () => now, intent);
      await Promise.all([first.recover(), second.recover()]);
      if (operation === 'preflight') now = 100_000;
      if (operation === 'switch-refresh')
        await first.switchTenantContext({ membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6081' });
      else await first.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
      await expect.poll(() => second.getState().status).toBe('anonymous');
      expect(first.getState().status).toBe('anonymous');
    },
  );

  it.each(['recover', 'login', 'selection', 'switch'])(
    'discards a superseded %s result rather than resurrecting a session',
    async (operation) => {
      const origin = browserOrigin();
      const intent = operation === 'selection' || operation === 'switch' ? 'TENANT' : 'PLATFORM';
      const context = tenantContext();
      const other = {
        membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6081',
        tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6082',
        tenantDisplayName: 'Other',
      };
      const token = () =>
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'superseded',
          tokenType: 'Bearer',
          expiresIn: 120,
          ...(intent === 'TENANT' ? { tenantContext: context } : {}),
        });
      let resolve!: (response: Response) => void;
      let ready = false;
      const tab = runtime(
        origin.realm(),
        (input) =>
          Promise.resolve().then(() => {
            if (!ready && operation === 'selection')
              return Response.json({
                contextState: 'CONTEXT_SELECTION_REQUIRED',
                memberships: [context.accessibleMemberships[0], other],
              });
            if (!ready && operation === 'switch') return token();
            if (ready && operation === 'switch' && requestUrl(input).endsWith('/refresh'))
              throw new Error('superseded switch must not refresh');
            return new Promise<Response>((done) => {
              resolve = done;
            });
          }),
        undefined,
        intent,
      );
      if (operation === 'selection')
        await tab.login({ email: 'member@example.test', password: 'secret' });
      if (operation === 'switch') await tab.recover();
      ready = true;
      const result =
        operation === 'recover'
          ? tab.recover()
          : operation === 'login'
            ? tab.login({ email: 'member@example.test', password: 'secret' })
            : operation === 'selection'
              ? tab.selectAuthenticationContext({ membershipId: context.membershipId })
              : tab.switchTenantContext({ membershipId: other.membershipId });
      await expect.poll(() => typeof resolve).toBe('function');
      origin.deliver({ event: 'session-ended', contextType: intent, generation: 10 });
      resolve(operation === 'switch' ? new Response(null, { status: 204 }) : token());
      expect((await result).ok).toBe(false);
      expect(tab.getState()).toEqual({ status: 'anonymous', transition: null });
    },
  );

  it('rejects a late refresh response after a newer session-end event even when logoutPending is clear', async () => {
    const origin = browserOrigin();
    let now = 0;
    let resolveRefresh!: (response: Response) => void;
    let requests = 0;
    const token = () =>
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: 'late-old-token',
        tokenType: 'Bearer',
        expiresIn: 120,
      });
    const tab = runtime(
      origin.realm(),
      () =>
        Promise.resolve().then(() => {
          if (++requests === 1) return token();
          if (requests > 2) return Response.json({});
          return new Promise<Response>((resolve) => {
            resolveRefresh = resolve;
          });
        }),
      () => now,
    );
    await tab.recover();
    now = 100_000;
    const business = tab.client.getOAuthClient({
      clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
    });
    await expect.poll(() => requests).toBe(2);
    origin.deliver({ event: 'session-ended', contextType: 'PLATFORM', generation: 2 });
    expect(tab.getState().status).toBe('anonymous');
    resolveRefresh(token());
    expect((await business).ok).toBe(false);
    expect(tab.getState().status).toBe('anonymous');
    expect(requests).toBe(2);
  });

  it('keeps the same refresh key and honors Retry-After during explicit recovery without browser coordination', async () => {
    let now = 0;
    const keys: (string | null)[] = [];
    const tab = runtime(
      {},
      (_input, init) =>
        Promise.resolve().then(() => {
          keys.push(new Headers(init?.headers).get('Idempotency-Key'));
          if (keys.length === 1) return problem(409, 'REFRESH_ROTATION_IN_PROGRESS', '2');
          return Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: 'recovered',
            tokenType: 'Bearer',
            expiresIn: 120,
          });
        }),
      () => now,
    );
    expect((await tab.recover()).ok).toBe(false);
    expect((await tab.retryRecovery()).ok).toBe(false);
    expect(keys).toHaveLength(1);
    now = 2_000;
    expect((await tab.retryRecovery()).ok).toBe(true);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it.each([
    'locks-missing',
    'channel-missing',
    'locks-getter',
    'channel-constructor',
    'storage-read',
    'storage-write',
    'lock-request',
    'channel-send',
  ])('falls back without replaying a session mutation when %s is unavailable', async (failure) => {
    const origin = browserOrigin();
    const realm = origin.realm();
    const unavailable = () => {
      throw new Error('browser capability unavailable');
    };
    if (failure === 'locks-missing') Object.assign(realm, { navigator: {} });
    if (failure === 'channel-missing') Object.assign(realm, { BroadcastChannel: undefined });
    if (failure === 'locks-getter') Object.defineProperty(realm, 'navigator', { get: unavailable });
    if (failure === 'channel-constructor')
      Object.assign(realm, {
        BroadcastChannel: class extends realm.BroadcastChannel {
          constructor() {
            super('unavailable-test-channel');
            unavailable();
          }
        },
      });
    if (failure === 'storage-read') realm.localStorage.getItem = unavailable;
    if (failure === 'storage-write') realm.localStorage.setItem = unavailable;
    if (failure === 'lock-request') realm.navigator.locks.request = unavailable;
    if (failure === 'channel-send') realm.BroadcastChannel.prototype.postMessage = unavailable;
    let requests = 0;
    const tab = runtime(realm, () =>
      Promise.resolve().then(() => {
        requests += 1;
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'memory-only-fallback',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      }),
    );
    expect(
      (await tab.login({ email: 'private@example.test', password: 'private-password' })).ok,
    ).toBe(true);
    expect(requests).toBe(1);
    expect(JSON.stringify([...origin.values])).not.toMatch(
      /memory-only-fallback|private@example|private-password/,
    );
  });

  it('waits for a queued broadcast when the browser grants the next lock before message delivery', async () => {
    const origin = browserOrigin(10);
    let refreshes = 0;
    const fetch = () =>
      Promise.resolve().then(() => {
        refreshes += 1;
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'delayed-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      });
    const first = runtime(origin.realm(), fetch);
    const second = runtime(origin.realm(), fetch);
    await Promise.all([first.recover(), second.recover()]);
    expect(refreshes).toBe(1);
    expect(second.getState()).toEqual(first.getState());
  });

  it('invalidates every old Tenant context at switch commit and serializes its refresh', async () => {
    const origin = browserOrigin();
    const old = tenantContext();
    const target = {
      ...old,
      membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6081',
      tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6082',
      tenantDisplayName: 'Target Tenant',
    };
    const next = {
      ...target,
      accessibleMemberships: [target],
      brandProfile: {
        displayName: 'Target Brand',
        primaryColor: '#155EEF',
        accentColor: '#7A5AF8',
      },
    };
    let switched = false;
    let resolveRefresh!: (response: Response) => void;
    let refreshes = 0;
    const token = (context: typeof old) =>
      Response.json({
        contextState: 'ACCESS_TOKEN_ISSUED',
        accessToken: switched ? 'new-tenant' : 'old-tenant',
        tokenType: 'Bearer',
        expiresIn: 120,
        tenantContext: context,
      });
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        const url = requestUrl(input);
        if (url.endsWith('/tenant-switches')) {
          switched = true;
          return new Response(null, { status: 204 });
        }
        if (url.endsWith('/context')) return Response.json(switched ? next : old);
        if (url.endsWith('/refresh')) {
          refreshes += 1;
          if (switched)
            return new Promise<Response>((resolve) => {
              resolveRefresh = resolve;
            });
          return token(old);
        }
        throw new Error('protected request must not run during switch');
      });
    const first = runtime(origin.realm(), fetch, undefined, 'TENANT');
    const second = runtime(origin.realm(), fetch, undefined, 'TENANT');
    await Promise.all([first.recover(), second.recover()]);
    const switchResult = first.switchTenantContext({ membershipId: target.membershipId });
    await expect.poll(() => second.getState().transition).toBe('sessionSync');
    expect(
      (await second.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' })).ok,
    ).toBe(false);
    resolveRefresh(token(next));
    expect((await switchResult).ok).toBe(true);
    await expect.poll(() => second.getState()).toEqual(first.getState());
    expect(refreshes).toBe(2);
  });

  it('ends the restricted session in every tab after the initial password change', async () => {
    const origin = browserOrigin();
    let changes = 0;
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        if (requestUrl(input).endsWith('/password-changes')) {
          changes += 1;
          return new Response(null, { status: 204 });
        }
        return Response.json({ contextState: 'PASSWORD_CHANGE_REQUIRED' });
      });
    const first = runtime(origin.realm(), fetch);
    const second = runtime(origin.realm(), fetch);
    await Promise.all([first.recover(), second.recover()]);
    expect(origin.values.get('sf:session:https://api.example.test:PLATFORM:generation')).toBe('2');
    await Promise.all([
      first.changeInitialPassword({ newPassword: 'new-password' }),
      second.changeInitialPassword({ newPassword: 'new-password' }),
    ]);
    expect(changes).toBe(1);
    expect(first.getState().status).toBe('anonymous');
    expect(second.getState().status).toBe('anonymous');
    expect(JSON.stringify(origin.messages)).not.toContain('new-password');
  });

  it('keeps membership candidates private until selection publishes an authenticated session', async () => {
    const origin = browserOrigin();
    const context = tenantContext();
    const candidates = [
      context.accessibleMemberships[0],
      {
        membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6081',
        tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6082',
        tenantDisplayName: 'Other Tenant',
      },
    ];
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        const url = requestUrl(input);
        if (url.endsWith('/login'))
          return Response.json({
            contextState: 'CONTEXT_SELECTION_REQUIRED',
            memberships: candidates,
          });
        if (url.endsWith('/context')) return Response.json(context);
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'selected-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: context,
        });
      });
    const first = runtime(origin.realm(), fetch, undefined, 'TENANT');
    const second = runtime(origin.realm(), fetch, undefined, 'TENANT');
    await first.login({ email: 'member@example.test', password: 'private-password' });
    expect(second.getState().status).toBe('anonymous');
    expect(origin.messages).toEqual([]);
    expect([...origin.values.values()]).toEqual(['1']);
    await first.selectAuthenticationContext({ membershipId: context.membershipId });
    await expect.poll(() => second.getState()).toEqual(first.getState());
    expect(JSON.stringify(origin.messages)).not.toMatch(
      /member@example|private-password|memberships|tenantDisplayName/,
    );
  });

  it('hydrates each receiving Tenant tab through the readonly context operation without another refresh', async () => {
    const origin = browserOrigin();
    const context = tenantContext();
    const requests: string[] = [];
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        const url = requestUrl(input);
        requests.push(url);
        if (url.endsWith('/auth/context')) return Response.json(context);
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'tenant-token',
          tokenType: 'Bearer',
          expiresIn: 120,
          tenantContext: context,
        });
      });
    const first = runtime(origin.realm(), fetch, undefined, 'TENANT');
    const second = runtime(origin.realm(), fetch, undefined, 'TENANT');
    await Promise.all([first.recover(), second.recover()]);
    await expect.poll(() => second.getState()).toEqual(first.getState());
    expect(requests.filter((url) => url.endsWith('/auth/refresh'))).toHaveLength(1);
    expect(requests.filter((url) => url.endsWith('/auth/context'))).toHaveLength(1);
  });

  it.each(['self', 'peer'])(
    'discards an in-flight refresh after %s requests logout',
    async (initiator) => {
      const origin = browserOrigin();
      let now = 0;
      let refreshes = 0;
      let resolveRefresh!: (response: Response) => void;
      let protectedRequests = 0;
      const token = () =>
        Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'old-context',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      const fetch = (input: RequestInfo | URL) =>
        Promise.resolve().then(() => {
          if (requestUrl(input).endsWith('/auth/refresh')) {
            if (++refreshes === 1) return token();
            return new Promise<Response>((resolve) => {
              resolveRefresh = resolve;
            });
          }
          if (requestUrl(input).endsWith('/logout')) return new Response(null, { status: 204 });
          protectedRequests += 1;
          return Response.json({});
        });
      const first = runtime(origin.realm(), fetch, () => now);
      const second = runtime(origin.realm(), fetch, () => now);
      await Promise.all([first.recover(), second.recover()]);
      now = 100_000;
      const request = first.client.getOAuthClient({
        clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
      });
      await expect.poll(() => refreshes).toBe(2);
      const logout = (initiator === 'self' ? first : second).logout();
      await expect.poll(() => first.getState().status).toBe('logoutPending');
      resolveRefresh(token());
      expect((await request).ok).toBe(false);
      await logout;
      expect(protectedRequests).toBe(0);
      expect(first.getState().status).toBe('anonymous');
      expect(second.getState().status).toBe('anonymous');
    },
  );

  it('blocks every tab immediately on logout and restores only the pending logout after reload', async () => {
    const origin = browserOrigin();
    let rejectLogout!: (reason: Error) => void;
    let retry = false;
    let refreshes = 0;
    const fetch = (input: RequestInfo | URL) =>
      Promise.resolve().then(() => {
        if (requestUrl(input).endsWith('/logout')) {
          if (retry) return new Response(null, { status: 204 });
          return new Promise<Response>((_resolve, reject) => {
            rejectLogout = reject;
          });
        }
        refreshes += 1;
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'before-logout',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      });
    const first = runtime(origin.realm(), fetch);
    const second = runtime(origin.realm(), fetch);
    await Promise.all([first.recover(), second.recover()]);
    const logout = first.logout();
    expect(
      (await second.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' })).ok,
    ).toBe(false);
    await expect.poll(() => second.getState().status).toBe('logoutPending');
    expect(
      (await second.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' })).ok,
    ).toBe(false);
    const reloaded = runtime(origin.realm(), fetch);
    expect(reloaded.getState().status).toBe('logoutPending');
    expect((await reloaded.recover()).ok).toBe(false);
    expect(refreshes).toBe(1);
    rejectLogout(new Error('unknown logout outcome'));
    expect((await logout).ok).toBe(false);
    retry = true;
    expect((await reloaded.logout()).ok).toBe(true);
    await expect.poll(() => second.getState().status).toBe('anonymous');
    expect(first.getState().status).toBe('anonymous');
  });

  it('serializes concurrent logins and shares only the authenticated result', async () => {
    const origin = browserOrigin();
    let logins = 0;
    const fetch = () =>
      Promise.resolve().then(() => {
        logins += 1;
        return Response.json({
          contextState: 'ACCESS_TOKEN_ISSUED',
          accessToken: 'login-token',
          tokenType: 'Bearer',
          expiresIn: 120,
        });
      });
    const tabs = [runtime(origin.realm(), fetch), runtime(origin.realm(), fetch)];
    const results = await Promise.all(
      tabs.map((tab) => tab.login({ email: 'test@example.test', password: 'secret' })),
    );
    expect(results.every((result) => result.ok)).toBe(true);
    expect(logins).toBe(1);
    expect(tabs[0].getState()).toEqual(tabs[1].getState());
  });

  it('shares one cold-start refresh and its token between two tabs', async () => {
    const origin = browserOrigin();
    const requests: { url: string; headers: Headers }[] = [];
    const fetch = (input: RequestInfo | URL, init?: RequestInit) =>
      Promise.resolve().then(() => {
        const url = requestUrl(input);
        requests.push({ url, headers: new Headers(init?.headers) });
        return url.endsWith('/auth/refresh')
          ? Response.json({
              contextState: 'ACCESS_TOKEN_ISSUED',
              accessToken: 'shared-token',
              tokenType: 'Bearer',
              expiresIn: 120,
            })
          : Response.json({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
      });
    const first = runtime(origin.realm(), fetch);
    const second = runtime(origin.realm(), fetch);

    const results = await Promise.all([first.recover(), second.recover()]);
    expect(results.every((result) => result.ok)).toBe(true);
    await second.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });

    expect(requests.filter((request) => request.url.endsWith('/auth/refresh'))).toHaveLength(1);
    expect(requests.at(-1)?.headers.get('Authorization')).toBe('Bearer shared-token');
    expect(first.getState()).toEqual(second.getState());
  });

  it('shares the preflight refresh before both tabs send protected requests', async () => {
    const origin = browserOrigin();
    let now = 0;
    let refreshes = 0;
    const authorizations: (string | null)[] = [];
    const fetch = (input: RequestInfo | URL, init?: RequestInit) =>
      Promise.resolve().then(() => {
        if (requestUrl(input).endsWith('/auth/refresh')) {
          refreshes += 1;
          return Response.json({
            contextState: 'ACCESS_TOKEN_ISSUED',
            accessToken: `token-${String(refreshes)}`,
            tokenType: 'Bearer',
            expiresIn: 120,
          });
        }
        authorizations.push(new Headers(init?.headers).get('Authorization'));
        return Response.json({});
      });
    const first = runtime(origin.realm(), fetch, () => now);
    const second = runtime(origin.realm(), fetch, () => now);
    await Promise.all([first.recover(), second.recover()]);
    now = 100_000;
    await Promise.all(
      [first, second].map((tab) =>
        tab.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' }),
      ),
    );
    expect(refreshes).toBe(2);
    expect(authorizations).toEqual(['Bearer token-2', 'Bearer token-2']);
  });
});

function runtime(
  realm: object,
  fetch: typeof globalThis.fetch,
  now?: () => number,
  intent: AuthenticationIntent = 'PLATFORM',
) {
  const result = createAuthenticationRuntimeAfterConfig(
    parseRuntimeConfig({ schemaVersion: 1, apiBaseUrl: 'https://api.example.test' }),
    { realm, fetch, intent, now },
  );
  if (!result.ok) throw new Error('invalid test config');
  return result.runtime;
}

function tenantContext() {
  const current = {
    membershipId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6071',
    tenantId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6072',
    tenantDisplayName: 'Current Tenant',
  };
  return {
    ...current,
    accessibleMemberships: [current],
    brandProfile: { displayName: 'Current Brand', primaryColor: '#155EEF', accentColor: '#7A5AF8' },
  };
}

function requestUrl(input: RequestInfo | URL): string {
  return typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
}

function problem(status: number, code: string, retryAfter: string) {
  return Response.json(
    {
      type: `urn:saasforge:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: 'Request unavailable',
      detail: 'test-only detail',
      status,
      code,
      traceId: '0123456789abcdef0123456789abcdef',
    },
    { status, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': retryAfter } },
  );
}

// 浏览器系统边界替身；测试只经 Runtime 公共接口观察状态和 HTTP 行为。
function browserOrigin(messageDelay?: number) {
  const values = new Map<string, string>();
  const messages: unknown[] = [];
  const queues = new Map<string, Promise<unknown>>();
  const channels = new Set<Channel>();
  const realms = new Set<EventTarget>();
  class Channel {
    public onmessage: ((event: MessageEvent) => void) | null = null;
    public constructor(public readonly name: string) {
      channels.add(this);
    }
    public postMessage(data: unknown) {
      messages.push(structuredClone(data));
      for (const channel of channels) {
        if (channel !== this && channel.name === this.name) {
          const deliver = () =>
            channel.onmessage?.(new MessageEvent('message', { data: structuredClone(data) }));
          if (messageDelay === undefined) queueMicrotask(deliver);
          else setTimeout(deliver, messageDelay);
        }
      }
    }
    public close() {
      channels.delete(this);
    }
  }
  return {
    values,
    messages,
    deliver: (data: unknown) => {
      for (const channel of channels) channel.onmessage?.(new MessageEvent('message', { data }));
    },
    realm: () => {
      const target = new EventTarget();
      realms.add(target);
      return Object.assign(target, {
        navigator: {
          locks: {
            request: (name: string, callback: () => Promise<unknown>) => {
              const result = (queues.get(name) ?? Promise.resolve()).then(callback);
              queues.set(
                name,
                result.catch(() => undefined),
              );
              return result;
            },
          },
        },
        BroadcastChannel: Channel,
        localStorage: {
          getItem: (key: string) => values.get(key) ?? null,
          setItem: (key: string, value: string) => {
            values.set(key, value);
            for (const other of realms) {
              if (other !== target)
                queueMicrotask(() =>
                  other.dispatchEvent(
                    Object.assign(new Event('storage'), { key, newValue: value }),
                  ),
                );
            }
          },
        },
      });
    },
  };
}
