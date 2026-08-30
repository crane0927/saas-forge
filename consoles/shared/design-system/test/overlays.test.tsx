import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  ActionMenu,
  DesignSystemProvider,
  IrreversibleDangerDialog,
  RecoverableDangerDialog,
  StandardDialog,
  UnsavedChangesDialog,
} from '../src';

afterEach(cleanup);

describe('Design System 共享浮层', () => {
  it('菜单可由鼠标选择，并在关闭后把焦点恢复到触发按钮', async () => {
    const action = vi.fn();
    render(
      <DesignSystemProvider>
        <ActionMenu
          label="更多操作"
          items={[
            { key: 'edit', label: '编辑' },
            { key: 'delete', label: '删除', danger: true, separatorBefore: true },
          ]}
          onAction={action}
        />
      </DesignSystemProvider>,
    );

    const trigger = screen.getByRole('button', { name: '更多操作' });
    fireEvent.click(trigger);
    fireEvent.click(await screen.findByRole('menuitem', { name: '编辑' }));

    expect(action).toHaveBeenCalledWith('edit');
    await waitFor(() => {
      expect(document.activeElement).toBe(trigger);
    });
  });

  it('普通弹窗支持关闭操作并恢复原触发元素', async () => {
    render(<StandardDialogHarness />);

    const trigger = screen.getByRole('button', { name: '打开普通弹窗' });
    trigger.focus();
    fireEvent.click(trigger);

    expect(await screen.findByRole('dialog', { name: '成员详情' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '关闭' }));

    await waitFor(() => {
      expect(screen.getByText('普通弹窗状态：已关闭')).toBeTruthy();
      expect(document.activeElement).toBe(trigger);
    });
  });

  it('可恢复危险确认显示对象与后果，默认聚焦取消且 Enter 不执行危险动作', async () => {
    const confirm = vi.fn();
    const cancel = vi.fn();
    render(
      <DesignSystemProvider>
        <RecoverableDangerDialog
          open
          title="停用租户"
          objectName="北辰科技"
          consequence="停用后成员暂时无法登录，管理员可以恢复。"
          actionLabel="停用租户"
          onCancel={cancel}
          onConfirm={confirm}
        />
      </DesignSystemProvider>,
    );

    expect(screen.getByText('北辰科技')).toBeTruthy();
    expect(screen.getByText('停用后成员暂时无法登录，管理员可以恢复。')).toBeTruthy();
    const cancelButton = screen.getByRole('button', { name: '取消' });
    await waitFor(() => {
      expect(document.activeElement).toBe(cancelButton);
    });

    fireEvent.keyDown(cancelButton, { key: 'Enter' });
    expect(confirm).not.toHaveBeenCalled();
    fireEvent.click(cancelButton);
    expect(cancel).toHaveBeenCalledOnce();
  });

  it('未保存确认支持 Tab 顺序，并且只由 Esc 关闭最上层弹窗', async () => {
    render(<LayeredDialogHarness />);

    fireEvent.click(screen.getByRole('button', { name: '编辑资料' }));
    const attemptClose = await screen.findByRole('button', { name: '尝试关闭' });
    attemptClose.focus();
    fireEvent.click(attemptClose);

    const continueButton = await screen.findByRole('button', { name: '继续编辑' });
    const discardButton = screen.getByRole('button', { name: '放弃修改' });
    await waitFor(() => {
      expect(document.activeElement).toBe(continueButton);
    });

    fireEvent.keyDown(continueButton, { key: 'Tab' });
    discardButton.focus();
    expect(document.activeElement).toBe(discardButton);
    fireEvent.keyDown(discardButton, { key: 'Tab', shiftKey: true });
    continueButton.focus();
    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => {
      expect(screen.getByText('保护层状态：已关闭')).toBeTruthy();
      expect(screen.getByText('编辑成员资料')).toBeTruthy();
      expect(document.activeElement).toBe(screen.getByRole('button', { name: '尝试关闭' }));
    });
  });

  it('未保存确认的安全默认操作支持空格激活', async () => {
    const continueEditing = vi.fn();
    render(
      <DesignSystemProvider>
        <UnsavedChangesDialog
          open
          onContinueEditing={continueEditing}
          onDiscard={() => undefined}
        />
      </DesignSystemProvider>,
    );

    const continueButton = screen.getByRole('button', { name: '继续编辑' });
    await waitFor(() => {
      expect(document.activeElement).toBe(continueButton);
    });
    fireEvent.keyDown(continueButton, { key: ' ' });
    fireEvent.keyUp(continueButton, { key: ' ' });
    fireEvent.click(continueButton);

    expect(continueEditing).toHaveBeenCalledOnce();
  });

  it('不可恢复操作要求精确输入，输入框 Enter 不执行，删除后聚焦下一行', async () => {
    render(<RemovalHarness />);

    const firstDelete = screen.getByRole('button', { name: '永久删除 北辰科技' });
    firstDelete.focus();
    fireEvent.click(firstDelete);

    const confirmation = await screen.findByRole('textbox', { name: '输入对象名称确认' });
    const confirmButton = screen.getByRole('button', { name: '永久删除' });
    expect(confirmButton.hasAttribute('disabled')).toBe(true);

    fireEvent.change(confirmation, { target: { value: '北辰' } });
    expect(confirmButton.hasAttribute('disabled')).toBe(true);
    fireEvent.keyDown(confirmation, { key: 'Enter' });
    expect(screen.getByText('结果：尚未执行')).toBeTruthy();

    fireEvent.change(confirmation, { target: { value: '北辰科技' } });
    expect(confirmButton.hasAttribute('disabled')).toBe(false);
    fireEvent.click(confirmButton);

    await waitFor(() => {
      expect(screen.getByText('结果：已永久删除北辰科技')).toBeTruthy();
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: '永久删除 云帆数据' }),
      );
    });
  });
});

function StandardDialogHarness() {
  const [open, setOpen] = useState(false);
  return (
    <DesignSystemProvider>
      <button
        type="button"
        onClick={() => {
          setOpen(true);
        }}
      >
        打开普通弹窗
      </button>
      <p>普通弹窗状态：{open ? '已打开' : '已关闭'}</p>
      <StandardDialog
        open={open}
        title="成员详情"
        onClose={() => {
          setOpen(false);
        }}
      >
        <p>成员状态正常。</p>
      </StandardDialog>
    </DesignSystemProvider>
  );
}

function LayeredDialogHarness() {
  const [editorOpen, setEditorOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  return (
    <DesignSystemProvider>
      <button
        type="button"
        onClick={() => {
          setEditorOpen(true);
        }}
      >
        编辑资料
      </button>
      <p>保护层状态：{leaveOpen ? '已打开' : '已关闭'}</p>
      <StandardDialog
        open={editorOpen}
        title="编辑成员资料"
        onClose={() => {
          setEditorOpen(false);
        }}
      >
        <button
          type="button"
          onClick={() => {
            setLeaveOpen(true);
          }}
        >
          尝试关闭
        </button>
      </StandardDialog>
      <UnsavedChangesDialog
        open={leaveOpen}
        onContinueEditing={() => {
          setLeaveOpen(false);
        }}
        onDiscard={() => {
          setLeaveOpen(false);
          setEditorOpen(false);
        }}
      />
    </DesignSystemProvider>
  );
}

function RemovalHarness() {
  const [rows, setRows] = useState(['北辰科技', '云帆数据']);
  const [target, setTarget] = useState<string | null>(null);
  const [result, setResult] = useState('尚未执行');
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const headingRef = useRef<HTMLHeadingElement>(null);
  const emptyRef = useRef<HTMLParagraphElement>(null);
  const targetIndex = target === null ? -1 : rows.indexOf(target);
  const nextName = targetIndex < 0 ? undefined : rows[targetIndex + 1];

  return (
    <DesignSystemProvider>
      <h2 ref={headingRef} tabIndex={-1}>
        租户列表
      </h2>
      {rows.length === 0 ? (
        <p ref={emptyRef} tabIndex={-1}>
          暂无租户
        </p>
      ) : (
        rows.map((name) => (
          <button
            key={name}
            ref={(element) => {
              if (element === null) {
                rowButtons.current.delete(name);
              } else {
                rowButtons.current.set(name, element);
              }
            }}
            type="button"
            onClick={() => {
              setTarget(name);
            }}
          >
            永久删除 {name}
          </button>
        ))
      )}
      <p aria-live="polite">结果：{result}</p>
      <IrreversibleDangerDialog
        open={target !== null}
        title="永久删除租户"
        objectName={target ?? ''}
        consequence="租户和全部配置将永久删除，此操作不可恢复。"
        actionLabel="永久删除"
        onCancel={() => {
          setTarget(null);
        }}
        onConfirm={() => {
          if (target !== null) {
            setRows((current) => current.filter((name) => name !== target));
            setResult(`已永久删除${target}`);
          }
          setTarget(null);
        }}
        removedObjectFocus={{
          nextRow: () =>
            nextName === undefined ? null : (rowButtons.current.get(nextName) ?? null),
          tableHeading: () => headingRef.current,
          emptyState: () => emptyRef.current,
        }}
      />
    </DesignSystemProvider>
  );
}
