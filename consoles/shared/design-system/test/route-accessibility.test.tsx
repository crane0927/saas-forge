import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { RouteFocusAnnouncement } from '../src';

afterEach(cleanup);

describe('Design System 路由无障碍通知', () => {
  it('路由切换后聚焦主标题并通知读屏软件', async () => {
    const { rerender } = render(
      <>
        <RouteFocusAnnouncement routeKey="members" pageTitle="成员管理" focusTargetId="title" />
        <h1 id="title" tabIndex={-1}>
          成员管理
        </h1>
      </>,
    );

    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '成员管理' }));
    });
    expect(screen.getByRole('status').textContent).toBe('成员管理');

    rerender(
      <>
        <RouteFocusAnnouncement routeKey="roles" pageTitle="角色管理" focusTargetId="title" />
        <h1 id="title" tabIndex={-1}>
          角色管理
        </h1>
      </>,
    );
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByRole('heading', { name: '角色管理' }));
    });
    expect(screen.getByRole('status').textContent).toBe('角色管理');
  });
});
