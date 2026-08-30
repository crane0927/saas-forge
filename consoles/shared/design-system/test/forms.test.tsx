import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  Button,
  CheckboxField,
  DesignSystemProvider,
  FormErrorSummary,
  FormLayout,
  FormRow,
  PasswordField,
  SelectField,
  TextField,
  UnsavedChangesDialog,
  useFormProblemFocus,
  useUnsavedChangesGuard,
  type FormErrorItem,
} from '../src';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Design System 共享表单流程', () => {
  it('公共入口提供标签上置的输入、密码、选择、复选框和关联错误', () => {
    render(
      <DesignSystemProvider>
        <FormLayout
          ariaLabel="成员表单"
          onSubmit={(event) => {
            event.preventDefault();
          }}
        >
          <FormErrorSummary errors={[{ fieldId: 'name', message: '请输入名称。' }]} />
          <FormRow>
            <TextField
              id="name"
              label="显示名称"
              value=""
              error="请输入名称。"
              onValueChange={() => undefined}
            />
            <PasswordField id="password" label="密码" value="" onValueChange={() => undefined} />
          </FormRow>
          <SelectField
            id="role"
            label="角色"
            value="administrator"
            options={[{ value: 'administrator', label: '管理员' }]}
            onValueChange={() => undefined}
          />
          <CheckboxField
            id="updates"
            label="接收状态更新"
            checked
            onCheckedChange={() => undefined}
          />
        </FormLayout>
      </DesignSystemProvider>,
    );

    expect(screen.getByRole('form', { name: '成员表单' })).toBeTruthy();
    const name = screen.getByRole('textbox', { name: '显示名称' });
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(name.getAttribute('aria-describedby')).toBe('name-error');
    expect(screen.getByLabelText('密码')).toBeTruthy();
    expect(screen.getByRole('combobox', { name: '角色' })).toBeTruthy();
    expect(screen.getByRole('checkbox', { name: '接收状态更新' })).toBeTruthy();
    expect(
      screen.getAllByRole('alert').some((alert) => alert.textContent.includes('请输入名称。')),
    ).toBe(true);
  });

  it('只在离开已编辑字段时校验该字段，提交时校验全部并聚焦首个问题', async () => {
    render(<BlurValidationHarness />);

    const name = screen.getByRole('textbox', { name: '显示名称' });
    const password = screen.getByLabelText('密码');
    expect(screen.queryByText('请输入显示名称。')).toBeNull();
    expect(screen.queryByText('密码至少需要 8 个字符。')).toBeNull();

    fireEvent.change(name, { target: { value: '北辰' } });
    fireEvent.change(name, { target: { value: '' } });
    expect(screen.queryByText('请输入显示名称。')).toBeNull();
    fireEvent.blur(name);
    expect(screen.getAllByText('请输入显示名称。').length).toBeGreaterThan(0);
    expect(screen.queryByText('密码至少需要 8 个字符。')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    expect(screen.getAllByText('密码至少需要 8 个字符。').length).toBeGreaterThan(0);
    await waitFor(() => {
      expect(document.activeElement).toBe(name);
    });
    expect(password.getAttribute('aria-invalid')).toBe('true');
  });

  it('失败后保留输入并聚焦表单错误，提交期间阻止重复发送且可重试成功', async () => {
    vi.useFakeTimers();
    const save = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    render(<SubmissionHarness onSave={save} />);

    const name = screen.getByRole('textbox', { name: '显示名称' });
    fireEvent.change(name, { target: { value: '北辰科技' } });
    const form = screen.getByRole('form', { name: '保存成员' });
    fireEvent.submit(form);
    fireEvent.submit(form);

    expect(screen.getByRole('button', { name: '正在保存成员' })).toBeTruthy();
    await act(async () => {
      await vi.runAllTimersAsync();
      await Promise.resolve();
      await vi.runAllTimersAsync();
    });
    expect(save).toHaveBeenCalledOnce();

    const summary = screen.getByRole('alert');
    expect(summary.textContent).toContain('成员服务暂时不可用');
    expect((name as HTMLInputElement).value).toBe('北辰科技');
    expect(document.activeElement).toBe(summary);

    fireEvent.submit(form);
    await act(async () => {
      await vi.runAllTimersAsync();
      await Promise.resolve();
      await vi.runAllTimersAsync();
    });
    expect(save).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('status').textContent).toContain('保存成功');
  });

  it('关闭、返回和切换动作共用未保存确认，取消后恢复原编辑位置', async () => {
    render(<UnsavedGuardHarness />);

    const input = screen.getByRole('textbox', { name: '显示名称' });
    fireEvent.change(input, { target: { value: '已修改' } });
    const returnButton = screen.getByRole('button', { name: '返回上一页' });
    returnButton.focus();
    fireEvent.click(returnButton);

    expect(await screen.findByRole('dialog', { name: '放弃未保存的修改？' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '继续编辑' }));
    await waitFor(() => {
      expect(document.activeElement).toBe(returnButton);
    });
    expect((input as HTMLInputElement).value).toBe('已修改');

    fireEvent.click(screen.getByRole('button', { name: '切换页面' }));
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }));
    expect(screen.getByText('结果：已切换')).toBeTruthy();

    fireEvent.change(input, { target: { value: '再次修改' } });
    const beforeUnload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(beforeUnload);
    expect(beforeUnload.defaultPrevented).toBe(true);
  });
});

function BlurValidationHarness() {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [editedName, setEditedName] = useState(false);
  const [errors, setErrors] = useState<{ readonly name?: string; readonly password?: string }>({});
  const { summaryRef, focusFirstProblem } = useFormProblemFocus();
  const summaryErrors: FormErrorItem[] = [
    ...(errors.name === undefined ? [] : [{ fieldId: 'blur-name', message: errors.name }]),
    ...(errors.password === undefined
      ? []
      : [{ fieldId: 'blur-password', message: errors.password }]),
  ];

  return (
    <DesignSystemProvider>
      <FormLayout
        ariaLabel="字段校验表单"
        onSubmit={(event) => {
          event.preventDefault();
          const nextErrors = {
            name: name.trim() === '' ? '请输入显示名称。' : undefined,
            password: password.length < 8 ? '密码至少需要 8 个字符。' : undefined,
          };
          setErrors(nextErrors);
          focusFirstProblem(nextErrors.name === undefined ? 'blur-password' : 'blur-name');
        }}
      >
        <FormErrorSummary ref={summaryRef} errors={summaryErrors} />
        <TextField
          id="blur-name"
          label="显示名称"
          value={name}
          error={errors.name}
          onValueChange={(value) => {
            setName(value);
            setEditedName(true);
          }}
          onBlur={(currentName) => {
            if (editedName) {
              setErrors((current) => ({
                ...current,
                name: currentName.trim() === '' ? '请输入显示名称。' : undefined,
              }));
            }
          }}
        />
        <PasswordField
          id="blur-password"
          label="密码"
          value={password}
          error={errors.password}
          onValueChange={setPassword}
        />
        <Button type="submit">保存</Button>
      </FormLayout>
    </DesignSystemProvider>
  );
}

function SubmissionHarness({ onSave }: { readonly onSave: () => Promise<boolean> }) {
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [serviceError, setServiceError] = useState<string>();
  const [saved, setSaved] = useState(false);
  const { summaryRef, focusFirstProblem } = useFormProblemFocus();

  return (
    <DesignSystemProvider>
      <FormLayout
        ariaLabel="保存成员"
        onSubmit={(event) => {
          event.preventDefault();
          if (submitting) {
            return;
          }
          setSubmitting(true);
          setServiceError(undefined);
          window.setTimeout(() => {
            void onSave().then((success) => {
              setSubmitting(false);
              if (success) {
                setSaved(true);
              } else {
                setServiceError('成员服务暂时不可用，输入内容已保留。');
                focusFirstProblem();
              }
            });
          }, 10);
        }}
      >
        <FormErrorSummary
          ref={summaryRef}
          errors={serviceError === undefined ? [] : [{ message: serviceError }]}
        />
        <TextField id="submit-name" label="显示名称" value={name} onValueChange={setName} />
        <Button type="submit" variant="primary" loading={submitting} loadingLabel="正在保存成员">
          保存成员
        </Button>
        {saved ? <p role="status">保存成功</p> : null}
      </FormLayout>
    </DesignSystemProvider>
  );
}

function UnsavedGuardHarness() {
  const [value, setValue] = useState('初始值');
  const [savedValue, setSavedValue] = useState('初始值');
  const [result, setResult] = useState('编辑中');
  const guard = useUnsavedChangesGuard(value !== savedValue);

  const leave = (nextResult: string) => {
    guard.requestDiscard(() => {
      setSavedValue(value);
      setResult(nextResult);
    });
  };

  return (
    <DesignSystemProvider>
      <TextField id="guard-name" label="显示名称" value={value} onValueChange={setValue} />
      <Button
        onClick={() => {
          leave('已关闭');
        }}
      >
        关闭表单
      </Button>
      <Button
        onClick={() => {
          leave('已返回');
        }}
      >
        返回上一页
      </Button>
      <Button
        onClick={() => {
          leave('已切换');
        }}
      >
        切换页面
      </Button>
      <p>结果：{result}</p>
      <UnsavedChangesDialog
        open={guard.confirmationOpen}
        onContinueEditing={guard.continueEditing}
        onDiscard={guard.discardChanges}
      />
    </DesignSystemProvider>
  );
}
