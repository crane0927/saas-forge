import {
  ActionMenu,
  DesignSystemProvider,
  IrreversibleDangerDialog,
  RecoverableDangerDialog,
  StandardDialog,
  UnsavedChangesDialog,
} from '@saas-forge/design-system';
import { StrictMode, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';

import './showcase.css';

const initialTenants = ['北辰科技', '云帆数据'];

function OverlayShowcase() {
  const [result, setResult] = useState('请选择一个场景进行操作。');
  const [standardOpen, setStandardOpen] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [profile, setProfile] = useState('平台管理员');
  const [recoverableOpen, setRecoverableOpen] = useState(false);
  const [tenants, setTenants] = useState(initialTenants);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const tableHeadingRef = useRef<HTMLHeadingElement>(null);
  const emptyStateRef = useRef<HTMLParagraphElement>(null);
  const targetIndex = deleteTarget === null ? -1 : tenants.indexOf(deleteTarget);
  const nextTenant = targetIndex < 0 ? undefined : tenants[targetIndex + 1];

  return (
    <main className="sf-showcase">
      <header className="sf-showcase-header">
        <p className="sf-showcase-eyebrow">PRIVATE COMPONENT SHOWCASE</p>
        <h1>共享浮层与危险操作确认</h1>
        <p>验证菜单、普通关闭、未保存保护和两级危险确认的键盘与焦点行为。</p>
        <ActionMenu
          label="展示菜单"
          items={[
            { key: 'preview', label: '预览公共浮层' },
            { key: 'remove', label: '危险菜单项', danger: true, separatorBefore: true },
          ]}
          onAction={(key) => {
            setResult(key === 'preview' ? '已从菜单选择预览。' : '已选择危险菜单项，尚未执行。');
          }}
        />
      </header>

      <p className="sf-showcase-result" role="status">
        {result}
      </p>

      <div className="sf-showcase-grid">
        <section className="sf-showcase-card" aria-labelledby="standard-title">
          <h2 id="standard-title">普通弹窗</h2>
          <p>关闭后焦点返回原触发按钮。</p>
          <button
            type="button"
            onClick={() => {
              setStandardOpen(true);
            }}
          >
            打开普通弹窗
          </button>
        </section>

        <section className="sf-showcase-card" aria-labelledby="unsaved-title">
          <h2 id="unsaved-title">未保存保护</h2>
          <p>存在修改时，关闭动作只打开最上层放弃确认。</p>
          <button
            type="button"
            onClick={() => {
              setEditorOpen(true);
            }}
          >
            编辑成员资料
          </button>
        </section>

        <section className="sf-showcase-card" aria-labelledby="recoverable-title">
          <h2 id="recoverable-title">可恢复危险操作</h2>
          <p>明确显示对象、后果和安全默认操作。</p>
          <button
            type="button"
            onClick={() => {
              setRecoverableOpen(true);
            }}
          >
            停用北辰科技
          </button>
        </section>

        <section className="sf-showcase-card" aria-labelledby="irreversible-title">
          <h2 id="irreversible-title" ref={tableHeadingRef} tabIndex={-1}>
            不可恢复危险操作
          </h2>
          <p>只有精确输入对象名称后，永久删除才可执行。</p>
          <div className="sf-showcase-rows">
            {tenants.length === 0 ? (
              <p ref={emptyStateRef} tabIndex={-1}>
                暂无租户
              </p>
            ) : (
              tenants.map((tenant) => (
                <div className="sf-showcase-row" key={tenant}>
                  <span>{tenant}</span>
                  <button
                    type="button"
                    ref={(element) => {
                      if (element === null) {
                        rowButtons.current.delete(tenant);
                      } else {
                        rowButtons.current.set(tenant, element);
                      }
                    }}
                    onClick={() => {
                      setDeleteTarget(tenant);
                    }}
                  >
                    永久删除 {tenant}
                  </button>
                </div>
              ))
            )}
          </div>
        </section>
      </div>

      <StandardDialog
        open={standardOpen}
        title="成员详情"
        onClose={() => {
          setStandardOpen(false);
        }}
      >
        <p>成员状态正常，最近一次登录来自受信任设备。</p>
      </StandardDialog>

      <StandardDialog
        open={editorOpen}
        title="编辑成员资料"
        onClose={() => {
          if (profile === '平台管理员') {
            setEditorOpen(false);
          } else {
            setLeaveOpen(true);
          }
        }}
        closeLabel="尝试关闭"
      >
        <label className="sf-showcase-field">
          <span>显示名称</span>
          <input
            value={profile}
            onChange={(event) => {
              setProfile(event.target.value);
            }}
          />
        </label>
      </StandardDialog>

      <UnsavedChangesDialog
        open={leaveOpen}
        onContinueEditing={() => {
          setLeaveOpen(false);
        }}
        onDiscard={() => {
          setProfile('平台管理员');
          setLeaveOpen(false);
          setEditorOpen(false);
          setResult('已放弃未保存的修改。');
        }}
      />

      <RecoverableDangerDialog
        open={recoverableOpen}
        title="停用租户"
        objectName="北辰科技"
        consequence="停用后成员暂时无法登录，管理员可以随时恢复。"
        actionLabel="停用租户"
        onCancel={() => {
          setRecoverableOpen(false);
        }}
        onConfirm={() => {
          setRecoverableOpen(false);
          setResult('北辰科技已停用，可以恢复。');
        }}
      />

      <IrreversibleDangerDialog
        open={deleteTarget !== null}
        title="永久删除租户"
        objectName={deleteTarget ?? ''}
        consequence="租户及其全部配置将永久删除，此操作不可恢复。"
        actionLabel="永久删除"
        onCancel={() => {
          setDeleteTarget(null);
        }}
        onConfirm={() => {
          if (deleteTarget !== null) {
            setTenants((current) => current.filter((tenant) => tenant !== deleteTarget));
            setResult(`${deleteTarget} 已永久删除。`);
          }
          setDeleteTarget(null);
        }}
        removedObjectFocus={{
          nextRow: () =>
            nextTenant === undefined ? null : (rowButtons.current.get(nextTenant) ?? null),
          tableHeading: () => tableHeadingRef.current,
          emptyState: () => emptyStateRef.current,
        }}
      />
    </main>
  );
}

const root = document.getElementById('root');
if (root === null) {
  throw new Error('Design System 展示入口缺少根元素。');
}

createRoot(root).render(
  <StrictMode>
    <DesignSystemProvider>
      <OverlayShowcase />
    </DesignSystemProvider>
  </StrictMode>,
);
