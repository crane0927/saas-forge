import {
  createAuthenticationRuntimeAfterConfig,
  type AuthenticationFetch,
} from '@saas-forge/app-runtime';
import { DesignSystemProvider } from '@saas-forge/design-system';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  PlanRecoveryPanel,
  QuotaDefinitionRecoveryPanel,
  SubscriptionRecoveryPanel,
  TenantCreationRecoveryPanel,
} from '../src';

afterEach(cleanup);
const id = '019535d9-0000-7000-8000-000000000002';
const key = '019535d9-0000-7000-8000-000000000001';
const panels = [
  { kind: 'creation', Panel: TenantCreationRecoveryPanel, field: 'tenantId' },
  { kind: 'quota', Panel: QuotaDefinitionRecoveryPanel, field: 'quotaDefinitionId' },
  { kind: 'plan', Panel: PlanRecoveryPanel, field: 'planId' },
  { kind: 'subscription', Panel: SubscriptionRecoveryPanel, field: 'subscriptionId' },
] as const;

for (const locale of ['zh-CN', 'en-US'] as const) {
  for (const { kind, Panel, field } of panels) {
    const read =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '读取创建记录'
          : '读取操作记录'
        : kind === 'creation'
          ? 'Read creation records'
          : 'Read operation records';
    const replay =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '继续原创建'
          : '继续原操作'
        : kind === 'creation'
          ? 'Continue original creation'
          : 'Continue original operation';
    const next =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '下一页创建记录'
          : '下一页操作记录'
        : kind === 'creation'
          ? 'Next creation records'
          : 'Next operation records';
    const previous =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '上一页创建记录'
          : '上一页操作记录'
        : kind === 'creation'
          ? 'Previous creation records'
          : 'Previous operation records';
    const states =
      locale === 'zh-CN'
        ? {
            COMMITTED: '已提交',
            PROCESSING: '仍在处理，请稍后重新读取',
            UNKNOWN: '无法确定，请核查',
            NOT_COMMITTED: '读取时确认未提交，可继续原操作',
          }
        : {
            COMMITTED: 'Committed',
            PROCESSING: 'Still processing. Read again later',
            UNKNOWN: 'Unknown. Verify the result',
            NOT_COMMITTED: 'Not committed at this read. The original operation can continue',
          };
    const failure =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '创建记录暂时无法读取'
          : '操作记录暂时无法读取'
        : kind === 'creation'
          ? 'Creation records are unavailable'
          : 'Operation records unavailable';
    const empty =
      locale === 'zh-CN'
        ? kind === 'creation'
          ? '未找到创建记录；这不能证明先前请求未提交。'
          : '未找到操作记录；这不能证明先前请求未提交。'
        : kind === 'creation'
          ? 'No creation records found. This does not prove an earlier request was not committed.'
          : 'No operation records found. This does not prove the request was not committed.';
    const operation = {
      id,
      idempotencyKey: key,
      displayName: 'Northstar',
      operation: 'CREATE',
      code: 'max_users',
      tenantId: id,
      planId: id,
      quotaDefinitionId: id,
      subscriptionId: id,
      createdAt: '2026-09-11T00:00:00.000Z',
      replayUntil: '2026-09-12T00:00:00.000Z',
      state: 'NOT_COMMITTED',
      canReplay: true,
    };
    async function mount(responses: readonly (Response | Promise<Response>)[]) {
      const replies = [...responses];
      const fetch = vi.fn<AuthenticationFetch>().mockImplementation(() => {
        const response = replies.shift();
        if (!response) throw new Error('Unexpected request');
        return Promise.resolve(response);
      });
      const runtime = createAuthenticationRuntimeAfterConfig(
        { ok: true, config: { schemaVersion: 1, apiBaseUrl: 'https://api.example.test' } },
        {
          realm: {},
          intent: 'PLATFORM',
          fetch: vi
            .fn<AuthenticationFetch>()
            .mockResolvedValueOnce(
              Response.json({
                contextState: 'ACCESS_TOKEN_ISSUED',
                accessToken: 'test-token',
                tokenType: 'Bearer',
                expiresIn: 120,
              }),
            )
            .mockImplementation(fetch),
        },
      );
      if (!runtime.ok) throw new Error('Invalid fixture configuration');
      await runtime.runtime.login({ email: 'test@example.test', password: 'test-only' });
      const view = vi.fn();
      render(
        <DesignSystemProvider locale={locale}>
          <Panel client={runtime.runtime.client} tenantId={id} locale={locale} onView={view} />
        </DesignSystemProvider>,
      );
      expect(fetch).not.toHaveBeenCalled();
      return { fetch, view };
    }
    describe(`${kind} ${locale} 公开恢复面板`, () => {
      it.each(['COMMITTED', 'PROCESSING', 'UNKNOWN', 'NOT_COMMITTED'] as const)(
        '%s 只显示权威状态允许的操作',
        async (state) => {
          const { fetch, view } = await mount([
            Response.json({ items: [{ ...operation, state }], nextCursor: null, hasMore: false }),
            Response.json({ ...operation, state: 'COMMITTED', [field]: id }),
          ]);
          fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
          const row = await screen.findByRole('listitem');
          expect(row.textContent).toContain(states[state]);
          expect(row.textContent).not.toContain(key);
          expect(fetch).toHaveBeenCalledOnce();
          if (state === 'NOT_COMMITTED') {
            fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: replay }));
            await waitFor(() => {
              expect(view).toHaveBeenCalledWith(id);
            });
            expect(new Headers(fetch.mock.calls[1][1]?.headers).get('Idempotency-Key')).toBe(key);
          } else {
            expect(screen.queryByRole('button', { name: replay })).toBeNull();
            expect(view).not.toHaveBeenCalled();
          }
        },
      );
      it('读取失败可以显式重试，空列表不能触发重放', async () => {
        const { fetch } = await mount([
          new Response(null, { status: 503 }),
          Response.json({ items: [], nextCursor: null, hasMore: false }),
        ]);
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
        expect((await screen.findByRole('alert')).textContent).toContain(failure);
        expect(fetch).toHaveBeenCalledOnce();
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
        await waitFor(() => {
          expect(screen.queryByRole('alert')).toBeNull();
        });
        expect(screen.getByText(empty)).toBeTruthy();
        expect(screen.queryByRole('button', { name: replay })).toBeNull();
        expect(screen.getByRole<HTMLButtonElement>('button', { name: next }).disabled).toBe(true);
      });
      it('禁止重放的权威记录不提供继续操作', async () => {
        const { fetch } = await mount([
          Response.json({
            items: [{ ...operation, canReplay: false }],
            nextCursor: null,
            hasMore: false,
          }),
        ]);
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
        expect((await screen.findByRole('listitem')).textContent).toContain(states.NOT_COMMITTED);
        expect(screen.queryByRole('button', { name: replay })).toBeNull();
        expect(fetch).toHaveBeenCalledOnce();
      });
      it('重放忙碌时阻止重复操作，失败后可显式重试并重新读取处理中状态', async () => {
        let finish!: (response: Response) => void;
        const pending = new Promise<Response>((resolve) => {
          finish = resolve;
        });
        const { fetch, view } = await mount([
          Response.json({ items: [operation], nextCursor: null, hasMore: false }),
          pending,
          Response.json({ ...operation, state: 'PROCESSING' }),
          Response.json({
            items: [{ ...operation, state: 'PROCESSING' }],
            nextCursor: null,
            hasMore: false,
          }),
        ]);
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
        await screen.findByRole('listitem');
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: replay }));
        expect(screen.getByRole<HTMLButtonElement>('button', { name: replay }).disabled).toBe(true);
        expect(screen.getByRole<HTMLButtonElement>('button', { name: read }).disabled).toBe(true);
        expect(screen.getByRole('status').textContent).toBe(
          locale === 'zh-CN' ? '正在读取…' : 'Reading…',
        );
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: replay }));
        await waitFor(() => {
          expect(fetch).toHaveBeenCalledTimes(2);
        });
        finish(new Response(null, { status: 503 }));
        expect((await screen.findByRole('alert')).textContent).toContain(failure);
        expect(view).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: replay }));
        await waitFor(() => {
          expect(screen.getByRole('listitem').textContent).toContain(states.PROCESSING);
        });
        expect(fetch).toHaveBeenCalledTimes(4);
        expect(screen.queryByRole('alert')).toBeNull();
        expect(screen.queryByRole('button', { name: replay })).toBeNull();
        expect(view).not.toHaveBeenCalled();
      });
      it('游标分页前进和返回保留原始查询边界', async () => {
        const { fetch } = await mount([
          Response.json({ items: [operation], nextCursor: 'opaque-next', hasMore: true }),
          Response.json({ items: [], nextCursor: null, hasMore: false }),
          Response.json({ items: [operation], nextCursor: 'opaque-next', hasMore: true }),
        ]);
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: read }));
        await screen.findByRole('listitem');
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: next }));
        await waitFor(() => {
          expect(screen.queryByRole('listitem')).toBeNull();
        });
        expect(requestUrl(fetch.mock.calls[1][0])).toContain('cursor=opaque-next');
        fireEvent.click(screen.getByRole<HTMLButtonElement>('button', { name: previous }));
        await screen.findByRole('listitem');
        expect(requestUrl(fetch.mock.calls[2][0])).not.toContain('cursor=');
      });
    });
  }
}

function requestUrl(input: RequestInfo | URL): string {
  return input instanceof Request ? input.url : input.toString();
}
