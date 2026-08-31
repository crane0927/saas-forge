import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';

import { TenantLocalRouteOutlet, TenantRoutes } from '../src/routes';

afterEach(cleanup);

describe('Tenant route tree', () => {
  it('renders an honest 404 and moves route focus to its title', async () => {
    render(
      <MemoryRouter initialEntries={['/not-implemented']}>
        <TenantRoutes />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeTruthy();
    expect(screen.getByText('404')).toBeTruthy();
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '页面不存在' }));
    });
    expect(screen.getByRole('status').textContent).toBe('页面不存在');
  });

  it('keeps the future local route mount as an outlet', () => {
    render(
      <MemoryRouter initialEntries={['/future-local']}>
        <Routes>
          <Route element={<TenantLocalRouteOutlet />}>
            <Route path="/future-local" element={<p>tenant local route outlet</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText('tenant local route outlet')).toBeTruthy();
  });
});
