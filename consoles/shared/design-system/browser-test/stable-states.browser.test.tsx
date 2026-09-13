import { useState, type ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import {
  ApplicationFatalError,
  ApplicationLoading,
  Button,
  CheckboxField,
  ConfigurationFailure,
  DesignSystemProvider,
  FormErrorSummary,
  FormLayout,
  FormRow,
  IrreversibleDangerDialog,
  PasswordField,
  RecoverableDangerDialog,
  SelectField,
  ServerTable,
  StandardDialog,
  TextField,
  UnsavedChangesDialog,
  type DesignSystemLocale,
} from '../src';
import { auditAccessibility } from './accessibility-audit';

let root: Root | undefined;
let container: HTMLDivElement | undefined;
afterEach(() => {
  root?.unmount();
  container?.remove();
  root = undefined;
  container = undefined;
});

function mount(children: ReactNode, locale: DesignSystemLocale, dark: boolean) {
  document.documentElement.lang = locale;
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  flushSync(() =>
    root?.render(
      <DesignSystemProvider locale={locale} forcedColorScheme={dark ? 'dark' : 'light'}>
        {children}
      </DesignSystemProvider>,
    ),
  );
}

async function checkStable(name: string) {
  await document.fonts.ready;
  await expect
    .poll(() => document.documentElement.scrollWidth)
    .toBeLessThanOrEqual(window.innerWidth);
  expect((await auditAccessibility(document.body)).violations).toEqual([]);
  if (import.meta.env.SF_VISUAL_SNAPSHOTS !== 'false') {
    await expect(page.getByTestId('stable-surface')).toMatchScreenshot(name);
  }
}

const labels = {
  'zh-CN': {
    name: '成员名称',
    password: '密码',
    role: '角色',
    enabled: '启用',
    error: '请输入成员名称。',
    summary: '请处理以下问题',
    save: '保存',
    cancel: '取消',
    close: '关闭',
    retry: '重试',
    title: '成员资料',
    consequence: '此操作影响成员访问。',
    confirm: '确认操作',
    instruction: '输入对象名称确认',
    continue: '继续编辑',
    discard: '放弃修改',
    empty: '暂无数据',
    failed: '加载失败',
    loading: '正在加载成员资料 loading',
  },
  'en-US': {
    name: 'Member name',
    password: 'Password',
    role: 'Role',
    enabled: 'Enabled',
    error: 'Enter a member name.',
    summary: 'Resolve the following issues',
    save: 'Save',
    cancel: 'Cancel',
    close: 'Close',
    retry: 'Retry',
    title: 'Member details',
    consequence: 'This operation affects member access.',
    confirm: 'Confirm action',
    instruction: 'Enter the object name to confirm',
    continue: 'Continue editing',
    discard: 'Discard changes',
    empty: 'No data',
    failed: 'Loading failed',
    loading: 'Loading Member details loading',
  },
} as const;

function FormStates({ locale }: { readonly locale: DesignSystemLocale }) {
  const t = labels[locale];
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [enabled, setEnabled] = useState(false);
  const [role, setRole] = useState('reader');
  return (
    <main data-testid="stable-surface">
      <h1>{t.title}</h1>
      <FormLayout ariaLabel={t.title} onSubmit={() => undefined}>
        <FormErrorSummary errors={[{ fieldId: 'member-name', message: t.error }]} />
        <FormRow>
          <TextField
            id="member-name"
            label={t.name}
            value={name}
            onValueChange={setName}
            error={t.error}
          />
          <PasswordField
            id="member-password"
            label={t.password}
            value={password}
            onValueChange={setPassword}
          />
        </FormRow>
        <SelectField
          id="member-role"
          label={t.role}
          value={role}
          onValueChange={setRole}
          options={[
            { value: 'reader', label: 'Reader' },
            { value: 'editor', label: 'Editor' },
          ]}
        />
        <CheckboxField
          id="member-enabled"
          label={t.enabled}
          checked={enabled}
          onCheckedChange={setEnabled}
        />
        <Button type="submit">{t.save}</Button>
      </FormLayout>
    </main>
  );
}

for (const locale of ['zh-CN', 'en-US'] as const) {
  const t = labels[locale];
  for (const dark of [false, true]) {
    for (const width of [1280, 390]) {
      const variant = `${locale}-${dark ? 'dark' : 'light'}-${String(width)}`;
      describe(variant, () => {
        it('表单错误、公开控件状态与键盘操作', async () => {
          await page.viewport(width, 1000);
          mount(<FormStates locale={locale} />, locale, dark);
          await expect.element(page.getByRole('heading', { name: t.title })).toBeVisible();
          const name = page.getByRole('textbox', { name: t.name });
          await expect.element(name).toHaveAttribute('aria-invalid', 'true');
          await name.fill('Northstar');
          await userEvent.tab();
          await expect.element(page.getByLabelText(t.password)).toHaveFocus();
          await page.getByLabelText(t.password).fill('test-only');
          await page.getByRole('checkbox', { name: t.enabled }).click();
          await expect.element(page.getByRole('checkbox', { name: t.enabled })).toBeChecked();
          await checkStable(`form-errors-${variant}`);
        });

        it('表格首次加载、空态与失败', async () => {
          await page.viewport(width, 1400);
          mount(
            <main data-testid="stable-surface">
              <h1>{t.title}</h1>
              {(['loading', 'empty', 'error'] as const).map((state) => (
                <ServerTable
                  key={state}
                  ariaLabel={`${t.title} ${state}`}
                  rows={[]}
                  rowKey={(row: { id: string }) => row.id}
                  columns={[{ key: 'name', title: t.name, render: () => 'Northstar' }]}
                  page={1}
                  pageSize={10}
                  total={0}
                  onTableChange={() => undefined}
                  initialLoading={state === 'loading'}
                  loadError={state === 'error' ? t.consequence : undefined}
                  onRetry={() => undefined}
                />
              ))}
            </main>,
            locale,
            dark,
          );
          await expect.element(page.getByRole('alert', { name: t.failed })).toBeVisible();
          await expect.element(page.getByRole('status', { name: t.empty })).toBeVisible();
          await expect.element(page.getByLabelText(t.loading)).toHaveAttribute('aria-busy', 'true');
          await checkStable(`table-states-${variant}`);
        });

        for (const kind of ['standard', 'recoverable', 'irreversible', 'unsaved'] as const) {
          it(`${kind} 弹窗、默认安全焦点和 Esc`, async () => {
            await page.viewport(width, 900);
            const cancel = vi.fn();
            const confirm = vi.fn();
            const props = {
              open: true,
              title: t.title,
              objectName: 'Northstar',
              consequence: t.consequence,
              actionLabel: t.confirm,
              onCancel: cancel,
              onConfirm: confirm,
            };
            mount(
              <>
                <main>
                  <h1>{t.title}</h1>
                </main>
                {kind === 'standard' ? (
                  <StandardDialog open title={t.title} onClose={cancel}>
                    {t.consequence}
                  </StandardDialog>
                ) : kind === 'recoverable' ? (
                  <RecoverableDangerDialog {...props} />
                ) : kind === 'irreversible' ? (
                  <IrreversibleDangerDialog {...props} />
                ) : (
                  <UnsavedChangesDialog open onContinueEditing={cancel} onDiscard={confirm} />
                )}
              </>,
              locale,
              dark,
            );
            const dialog = page.getByRole('dialog');
            await expect.element(dialog).toBeVisible();
            dialog.element().setAttribute('data-testid', 'stable-surface');
            const safe = page.getByRole('button', {
              name: kind === 'standard' ? t.close : kind === 'unsaved' ? t.continue : t.cancel,
              exact: true,
            });
            await expect.element(safe).toHaveFocus();
            await checkStable(`${kind}-dialog-${variant}`);
            if (kind === 'irreversible') {
              await expect.element(page.getByRole('button', { name: t.confirm })).toBeDisabled();
              await page.getByLabelText(t.instruction).fill('Northstar');
              await expect.element(page.getByRole('button', { name: t.confirm })).toBeEnabled();
            }
            await userEvent.keyboard('{Escape}');
            expect(cancel).toHaveBeenCalledOnce();
            expect(confirm).not.toHaveBeenCalled();
          });
        }

        for (const state of ['loading', 'configuration', 'fatal'] as const) {
          it(`${state} 应用恢复画面`, async () => {
            await page.viewport(width, 900);
            mount(
              <div data-testid="stable-surface">
                {state === 'loading' ? (
                  <ApplicationLoading applicationName="Console" />
                ) : state === 'configuration' ? (
                  <ConfigurationFailure
                    applicationName="Console"
                    errorCode="CONFIG_UNAVAILABLE"
                    onRetry={() => undefined}
                  />
                ) : (
                  <ApplicationFatalError applicationName="Console" onReload={() => undefined} />
                )}
              </div>,
              locale,
              dark,
            );
            await expect.element(page.getByRole('heading', { level: 1 })).toBeVisible();
            await checkStable(`${state}-${variant}`);
          });
        }
      });
    }
  }
}
