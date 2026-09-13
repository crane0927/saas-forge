import { useState } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { page, userEvent } from 'vitest/browser';

import { DesignSystemProvider, RecoverableDangerDialog, ServerTable } from '../src';

let root: Root | undefined;
let container: HTMLDivElement | undefined;

afterEach(() => {
  root?.unmount();
  container?.remove();
  root = undefined;
  container = undefined;
});

describe('Design System 表格操作真实浏览器集成', () => {
  // Table 列测量、Dropdown Portal 与可见性在浏览器验证，避免 jsdom 样式计算支配此交互门禁。
  it('每行操作超过三个时只显示前两个和更多，并可进入危险确认', async () => {
    await page.viewport(1440, 960);
    container = document.createElement('div');
    document.body.append(container);
    root = createRoot(container);
    flushSync(() => {
      root?.render(<TableActionsHarness />);
    });
    const firstRow = page.getByRole('row', { name: /北辰科技/ });
    await expect.element(firstRow.getByRole('button', { name: '查看', exact: true })).toBeVisible();
    await expect.element(firstRow.getByRole('button', { name: '编辑', exact: true })).toBeVisible();
    await expect
      .element(firstRow.getByRole('button', { name: '停用', exact: true }))
      .not.toBeInTheDocument();
    await expect
      .element(firstRow.getByRole('button', { name: '复制', exact: true }))
      .not.toBeInTheDocument();
    await firstRow.getByRole('button', { name: '更多', exact: true }).click();
    await expect.element(page.getByRole('menuitem', { name: '复制', exact: true })).toBeVisible();
    await page.getByRole('menuitem', { name: '停用', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '停用租户', exact: true });
    await expect.element(dialog).toBeVisible();
    await expect.element(dialog.getByText('北辰科技', { exact: true })).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await expect.element(dialog).not.toBeInTheDocument();
  });
});

function TableActionsHarness() {
  const [target, setTarget] = useState<{ readonly id: string; readonly name: string }>();
  return (
    <DesignSystemProvider>
      <ServerTable
        ariaLabel="租户操作列表"
        rows={[
          { id: 'tenant-1', name: '北辰科技' },
          { id: 'tenant-2', name: '云帆数据' },
        ]}
        rowKey={(row) => row.id}
        columns={[{ key: 'name', title: '租户名称', render: (row) => row.name }]}
        actions={[
          { key: 'view', label: '查看', onAction: () => undefined },
          { key: 'edit', label: '编辑', onAction: () => undefined },
          { key: 'copy', label: '复制', onAction: () => undefined },
          { key: 'disable', label: '停用', danger: true, onAction: setTarget },
        ]}
        page={1}
        pageSize={2}
        total={2}
        onTableChange={() => undefined}
      />
      <RecoverableDangerDialog
        open={target !== undefined}
        title="停用租户"
        objectName={target?.name ?? ''}
        consequence="停用后成员暂时无法登录，管理员可以恢复。"
        actionLabel="停用租户"
        onCancel={() => {
          setTarget(undefined);
        }}
        onConfirm={() => {
          setTarget(undefined);
        }}
      />
    </DesignSystemProvider>
  );
}
