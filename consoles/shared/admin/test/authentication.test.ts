import { effectScope } from 'vue';
import { describe, expect, it } from 'vitest';
import type {
  AuthenticationIntent,
  AuthenticationListener,
  AuthenticationState,
} from '@saas-forge/app-runtime';
import { useAuthenticationRuntime } from '../src/runtime/authentication';

function observer(intent: AuthenticationIntent, initial: AuthenticationState) {
  let current = initial;
  const listeners = new Set<AuthenticationListener>();
  const scope = effectScope();
  const projection = scope.run(() =>
    useAuthenticationRuntime({
      intent,
      getState: () => current,
      subscribe(listener) {
        listeners.add(listener);
        return () => listeners.delete(listener);
      },
    }),
  )!;
  return {
    projection,
    scope,
    listeners,
    publish(next: AuthenticationState) {
      current = next;
      for (const listener of listeners) listener(next);
    },
  };
}

describe('Vue authentication projection', () => {
  it('keeps tenant business hidden until the authoritative context is ready', () => {
    const value = observer('TENANT', { status: 'authenticated', transition: null });
    expect(value.projection.businessReady.value).toBe(false);
    const tenantContext = {
      membershipId: 'membership',
      tenantId: 'tenant',
      tenantDisplayName: 'Tenant',
      accessibleMemberships: [],
    };
    value.publish({ status: 'authenticated', transition: null, tenantContext });
    expect(value.projection.businessReady.value).toBe(true);
    expect(
      value.projection.state.value.status === 'authenticated' &&
        value.projection.state.value.tenantContext,
    ).toBe(tenantContext);
    for (const transition of ['sessionSync', 'tenantSwitchRefresh', 'logout'] as const) {
      value.publish({ status: 'authenticated', transition, tenantContext });
      expect(value.projection.businessReady.value).toBe(false);
    }
    value.publish({
      status: 'authenticated',
      transition: null,
      tenantContext,
      synchronizationProblem: { code: 'NETWORK_UNAVAILABLE' },
    });
    expect(value.projection.businessReady.value).toBe(false);
    value.scope.stop();
  });

  it('keeps platform content mounted during an ordinary credential refresh', () => {
    const value = observer('PLATFORM', { status: 'authenticated', transition: 'refresh' });
    expect(value.projection.businessReady.value).toBe(true);
    value.publish({ status: 'logoutPending', transition: null });
    expect(value.projection.businessReady.value).toBe(false);
    value.scope.stop();
  });

  it('does not retain a subscription after the owning Vue scope is disposed', () => {
    const value = observer('PLATFORM', { status: 'anonymous', transition: null });
    expect(value.listeners.size).toBe(1);
    value.scope.stop();
    expect(value.listeners.size).toBe(0);
    value.publish({ status: 'authenticated', transition: null });
    expect(value.projection.state.value.status).toBe('anonymous');
  });
});
