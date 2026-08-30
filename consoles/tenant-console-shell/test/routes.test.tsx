import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';

import { TenantLocalRouteOutlet, TenantRoutes } from '../src/routes';

afterEach(cleanup);

describe('Tenant route tree', () => {
  it('renders an honest 404 for an unmatched route', () => {
    render(
      <MemoryRouter initialEntries={['/not-implemented']}>
        <TenantRoutes />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeTruthy();
    expect(screen.getByText('404')).toBeTruthy();
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
