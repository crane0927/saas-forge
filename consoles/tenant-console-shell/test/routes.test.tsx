import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter } from 'react-router';

import { createTenantAuthenticationRoutes, tenantAuthenticationRoutes } from '../src/routes';

afterEach(cleanup);

describe('Tenant route tree', () => {
  it('registers only the Tenant host local routes consumed by the shared shell', () => {
    expect(tenantAuthenticationRoutes.map(({ path, label }) => ({ path, label }))).toEqual([
      { path: '/', label: '工作台' },
    ]);
  });

  it('moves focus and announces the Tenant workspace route', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>{tenantAuthenticationRoutes[0]?.element}</MemoryRouter>,
    );

    const heading = screen.getByRole('heading', { name: 'Tenant 工作台' });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    expect(screen.getByRole('status').textContent).toBe('Tenant 工作台');
  });

  it('uses the active Locale for Tenant navigation, routes, and accessibility announcements', async () => {
    const routes = createTenantAuthenticationRoutes('en-US');
    expect(routes.map(({ path, label }) => ({ path, label }))).toEqual([
      { path: '/', label: 'Workspace' },
    ]);

    render(<MemoryRouter initialEntries={['/']}>{routes[0]?.element}</MemoryRouter>);

    const heading = screen.getByRole('heading', { name: 'Tenant workspace' });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    expect(screen.getByRole('status').textContent).toBe('Tenant workspace');
    expect(
      screen.getByText(
        'The current session was restored or signed in through the Tenant authentication Runtime.',
      ),
    ).toBeTruthy();
  });
});
