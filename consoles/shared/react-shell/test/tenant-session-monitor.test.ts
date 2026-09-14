import { afterEach, describe, expect, it, vi } from 'vitest';

describe('Tenant session monitor scheduling', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('schedules passive checks and cancels only the matching request deadline', async () => {
    vi.useFakeTimers();
    const postMessage = vi.fn();
    const scope = { postMessage, onmessage: undefined as unknown as (event: MessageEvent) => void };
    vi.stubGlobal('self', scope);
    await import('../src/tenant-session-monitor.worker');
    vi.advanceTimersByTime(20_000);
    expect(postMessage).toHaveBeenCalledWith({ type: 'tick' });
    scope.onmessage(new MessageEvent('message', { data: { type: 'start', sequence: 1 } }));
    scope.onmessage(new MessageEvent('message', { data: { type: 'start', sequence: 2 } }));
    scope.onmessage(new MessageEvent('message', { data: { type: 'finish', sequence: 1 } }));
    vi.advanceTimersByTime(5_000);
    expect(postMessage).toHaveBeenCalledWith({ type: 'deadline', sequence: 2 });
    expect(postMessage).not.toHaveBeenCalledWith({ type: 'deadline', sequence: 1 });
    postMessage.mockClear();
    scope.onmessage(new MessageEvent('message', { data: { type: 'start', sequence: 3 } }));
    scope.onmessage(new MessageEvent('message', { data: { type: 'finish', sequence: 3 } }));
    vi.advanceTimersByTime(5_000);
    expect(postMessage).not.toHaveBeenCalled();
  });
});
