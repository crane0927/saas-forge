import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApplicationShell } from '../src';

afterEach(cleanup);

describe('ApplicationShell', () => {
  it('provides named global navigation and keeps the page content in the main landmark', () => {
    const navigate = vi.fn();

    render(
      <ApplicationShell
        applicationName="Platform Console"
        navigationItems={[
          { href: '/', label: '首页', current: true },
          { href: '/oauth-clients', label: 'OAuth Client' },
        ]}
        onNavigate={navigate}
        actions={<button type="button">退出登录</button>}
      >
        <h1>Platform 首页</h1>
      </ApplicationShell>,
    );

    expect(screen.getByRole('navigation', { name: 'Platform Console 全局导航' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '首页' }).getAttribute('aria-current')).toBe('page');
    fireEvent.click(screen.getByRole('link', { name: 'OAuth Client' }));
    expect(navigate).toHaveBeenCalledWith('/oauth-clients');
    expect(screen.getByRole('main').contains(screen.getByRole('heading'))).toBe(true);
  });
});
