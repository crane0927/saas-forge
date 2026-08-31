import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';

import {
  PlatformProtectedAreaOutlet,
  PlatformPublicAreaOutlet,
  PlatformRoutes,
} from '../src/routes';

afterEach(cleanup);

describe('Platform route tree', () => {
  it('renders an honest 404 and moves route focus to its title', async () => {
    render(
      <MemoryRouter initialEntries={['/not-implemented']}>
        <PlatformRoutes />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '页面不存在' })).toBeTruthy();
    expect(screen.getByText('404')).toBeTruthy();
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '页面不存在' }));
    });
    expect(screen.getByRole('status').textContent).toBe('页面不存在');
  });

  it.each([
    ['public', PlatformPublicAreaOutlet],
    ['protected', PlatformProtectedAreaOutlet],
  ])('keeps the future %s area as an outlet', (area, AreaOutlet) => {
    render(
      <MemoryRouter initialEntries={['/future']}>
        <Routes>
          <Route element={<AreaOutlet />}>
            <Route path="/future" element={<p>{area} route outlet</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText(`${area} route outlet`)).toBeTruthy();
  });
});
