import {
  ActionMenu,
  Button,
  DesignSystemProvider,
  EmptyDataState,
  FilteredEmptyState,
  InitialContentLoading,
  IrreversibleDangerDialog,
  Link,
  LoadFailureState,
  NotFoundState,
  PageTitle,
  PersistentError,
  RecoverableDangerDialog,
  RefreshingContent,
  StandardDialog,
  SuccessFeedback,
  UnsavedChangesDialog,
  WarningFeedback,
} from '@saas-forge/design-system';
import { StrictMode, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';

import './showcase.css';

const initialTenants = ['北辰科技', '云帆数据'];
type PreviewState =
  'default' | 'loading' | 'disabled' | 'success' | 'error' | 'empty' | 'filtered' | 'not-found';

function OverlayShowcase() {
  const [result, setResult] = useState('请选择一个场景进行操作。');
  const [standardOpen, setStandardOpen] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [profile, setProfile] = useState('平台管理员');
  const [recoverableOpen, setRecoverableOpen] = useState(false);
  const [tenants, setTenants] = useState(initialTenants);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [previewState, setPreviewState] = useState<PreviewState>('default');
  const [successSequence, setSuccessSequence] = useState(0);
  const rowButtons = useRef(new Map<string, HTMLButtonElement>());
  const tableHeadingRef = useRef<HTMLHeadingElement>(null);
  const emptyStateRef = useRef<HTMLParagraphElement>(null);
  const targetIndex = deleteTarget === null ? -1 : tenants.indexOf(deleteTarget);
  const nextTenant = targetIndex < 0 ? undefined : tenants[targetIndex + 1];

  return (
    <main className="sf-showcase">
      <header className="sf-showcase-header">
        <p className="sf-showcase-eyebrow">PRIVATE COMPONENT SHOWCASE</p>
        <PageTitle description="验证页面结构、状态反馈、恢复动作、加载范围与共享浮层行为。">
          SaaS Forge Design System
        </PageTitle>
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

      <section className="sf-showcase-section" aria-labelledby="state-preview-title">
        <div className="sf-showcase-section-heading">
          <div>
            <h2 id="state-preview-title">页面状态与反馈</h2>
            <p>公共入口提供可区分的说明、作用范围和恢复动作。</p>
          </div>
          <Link href="#overlay-preview-title">查看浮层能力</Link>
        </div>
        <div className="sf-showcase-controls" aria-label="选择展示状态">
          {(
            [
              ['default', '默认'],
              ['loading', '加载'],
              ['disabled', '禁用'],
              ['success', '成功'],
              ['error', '错误'],
              ['empty', '无数据'],
              ['filtered', '筛选无结果'],
              ['not-found', '404'],
            ] as const
          ).map(([state, label]) => (
            <Button
              key={state}
              variant={previewState === state ? 'primary' : 'secondary'}
              onClick={() => {
                setPreviewState(state);
                if (state === 'success') {
                  setSuccessSequence((current) => current + 1);
                }
              }}
            >
              {label}
            </Button>
          ))}
        </div>
        <div className="sf-showcase-preview">
          {previewState === 'loading' ? (
            <>
              <InitialContentLoading />
              <RefreshingContent refreshing>
                <p>现有成员列表在局部刷新期间保持可见。</p>
              </RefreshingContent>
              <Button loading loadingLabel="正在保存成员">
                保存成员
              </Button>
            </>
          ) : null}
          {previewState === 'disabled' ? <Button disabled>不可用操作</Button> : null}
          {previewState === 'success' ? (
            <SuccessFeedback key={successSequence} message="成员已保存，提示将自动消失。" />
          ) : null}
          {previewState === 'error' ? (
            <PersistentError
              title="成员加载失败"
              action={{
                label: '重试',
                onAction: () => {
                  setResult('已保留筛选条件并重试。');
                  setPreviewState('default');
                },
              }}
              onClose={() => {
                setPreviewState('default');
              }}
            >
              请检查网络后重试；错误会持续显示，直到恢复或关闭。
            </PersistentError>
          ) : null}
          {previewState === 'empty' ? (
            <EmptyDataState
              description="创建第一个成员后将在这里显示。"
              action={{
                label: '新增成员',
                onAction: () => {
                  setResult('已选择新增成员。');
                },
              }}
            />
          ) : null}
          {previewState === 'filtered' ? (
            <FilteredEmptyState
              onReset={() => {
                setResult('已重置筛选条件。');
                setPreviewState('default');
              }}
            />
          ) : null}
          {previewState === 'not-found' ? (
            <NotFoundState
              returnLabel="返回默认状态"
              onReturn={() => {
                setPreviewState('default');
              }}
            />
          ) : null}
          {previewState === 'default' ? (
            <>
              <WarningFeedback title="配额即将用尽">剩余 5 个成员席位。</WarningFeedback>
              <p>默认内容可继续操作，页面未被全屏加载层阻断。</p>
              <Button
                variant="primary"
                onClick={() => {
                  setPreviewState('success');
                }}
              >
                保存并显示成功反馈
              </Button>
              <LoadFailureState
                description="此处单独演示加载失败；重试不会清空当前筛选条件。"
                onRetry={() => {
                  setResult('已保留筛选条件并重试。');
                }}
              />
            </>
          ) : null}
        </div>
      </section>

      <section className="sf-showcase-section" aria-labelledby="overlay-preview-title">
        <div className="sf-showcase-section-heading">
          <div>
            <h2 id="overlay-preview-title">共享浮层与危险操作确认</h2>
            <p>验证普通关闭、未保存保护和两级危险确认的键盘与焦点行为。</p>
          </div>
        </div>
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
      </section>

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
