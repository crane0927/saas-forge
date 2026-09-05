import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter } from 'react-router';

import { createPlatformAuthenticationRoutes, platformAuthenticationRoutes } from '../src/routes';

afterEach(cleanup);

describe('Platform route tree', () => {
  it('registers only the Platform local routes consumed by the shared shell', () => {
    expect(platformAuthenticationRoutes.map(({ path, label }) => ({ path, label }))).toEqual([
      { path: '/', label: '首页' },
      { path: '/oauth-clients', label: 'OAuth Client' },
    ]);
  });

  it('moves focus and announces the Platform overview route', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        {platformAuthenticationRoutes[0]?.element}
      </MemoryRouter>,
    );

    const heading = screen.getByRole('heading', { name: 'Platform 总览' });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    expect(screen.getByRole('status').textContent).toBe('Platform 总览');
  });

  it('uses the active Locale for Platform navigation, routes, and accessibility announcements', async () => {
    const routes = createPlatformAuthenticationRoutes('en-US');
    expect(routes.map(({ path, label }) => ({ path, label }))).toEqual([
      { path: '/', label: 'Home' },
      { path: '/oauth-clients', label: 'OAuth Client' },
    ]);

    render(<MemoryRouter initialEntries={['/']}>{routes[0]?.element}</MemoryRouter>);

    const heading = screen.getByRole('heading', { name: 'Platform overview' });
    await waitFor(() => {
      expect(document.activeElement).toBe(heading);
    });
    expect(screen.getByRole('status').textContent).toBe('Platform overview');
    expect(
      screen.getByText(
        'The current session was restored or signed in through the Platform authentication Runtime.',
      ),
    ).toBeTruthy();
  });
});
