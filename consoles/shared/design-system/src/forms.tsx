import { Checkbox as AntCheckbox, Input as AntInput, Select as AntSelect } from 'antd';
import { createTranslator, defineMessages } from '@saas-forge/i18n';
import {
  forwardRef,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FocusEvent,
  type ReactNode,
  type SubmitEventHandler,
} from 'react';

import enUS from './messages/forms/en-US.json';
import zhCN from './messages/forms/zh-CN.json';
import { useDesignSystemLocale } from './theme-provider';

export interface FormLayoutProps {
  readonly children: ReactNode;
  readonly onSubmit: SubmitEventHandler<HTMLFormElement>;
  readonly ariaLabel?: string;
}

export interface FormRowProps {
  readonly children: ReactNode;
}

export interface TextFieldProps {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly onValueChange: (value: string) => void;
  readonly onBlur?: (value: string) => void;
  readonly error?: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
  readonly placeholder?: string;
  readonly autoComplete?: string;
}

export type PasswordFieldProps = TextFieldProps;

export interface SelectFieldOption {
  readonly value: string;
  readonly label: string;
  readonly disabled?: boolean;
}

export interface SelectFieldProps {
  readonly id: string;
  readonly label: string;
  readonly value?: string;
  readonly options: readonly SelectFieldOption[];
  readonly onValueChange: (value: string) => void;
  readonly onBlur?: (value: string) => void;
  readonly error?: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
  readonly placeholder?: string;
}

export interface CheckboxFieldProps {
  readonly id: string;
  readonly label: string;
  readonly checked: boolean;
  readonly onCheckedChange: (checked: boolean) => void;
  readonly onBlur?: (checked: boolean) => void;
  readonly error?: string;
  readonly disabled?: boolean;
}

export interface FieldErrorProps {
  readonly id: string;
  readonly children: ReactNode;
}

export interface FormErrorItem {
  readonly message: string;
  readonly fieldId?: string;
}

export interface FormErrorSummaryProps {
  readonly errors: readonly FormErrorItem[];
  readonly title?: string;
}

export interface FormProblemFocus {
  readonly summaryRef: React.RefObject<HTMLDivElement | null>;
  readonly focusFirstProblem: (firstFieldId?: string) => void;
}

export interface UnsavedChangesGuard {
  readonly confirmationOpen: boolean;
  readonly requestDiscard: (action: () => void) => void;
  readonly continueEditing: () => void;
  readonly discardChanges: () => void;
}

const formMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

function useFormTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/forms',
    locale: useDesignSystemLocale(),
    messages: formMessages,
  });
}

export function FormLayout({ children, onSubmit, ariaLabel }: FormLayoutProps) {
  return (
    <form className="sf-form" noValidate onSubmit={onSubmit} aria-label={ariaLabel}>
      {children}
    </form>
  );
}

export function FormRow({ children }: FormRowProps) {
  return <div className="sf-form-row">{children}</div>;
}

export function TextField(props: TextFieldProps) {
  return <InputField {...props} password={false} />;
}

export function PasswordField(props: PasswordFieldProps) {
  return <InputField {...props} password />;
}

function InputField({
  id,
  label,
  value,
  onValueChange,
  onBlur,
  error,
  required = false,
  disabled = false,
  placeholder,
  autoComplete,
  password,
}: TextFieldProps & { readonly password: boolean }) {
  const errorId = `${id}-error`;
  const sharedProps = {
    id,
    value,
    disabled,
    placeholder,
    autoComplete,
    status: error === undefined ? undefined : ('error' as const),
    'aria-invalid': error === undefined ? undefined : true,
    'aria-describedby': error === undefined ? undefined : errorId,
    'aria-required': required,
    onChange: (event: ChangeEvent<HTMLInputElement>) => {
      onValueChange(event.target.value);
    },
    onBlur: (event: FocusEvent<HTMLInputElement>) => {
      onBlur?.(event.currentTarget.value);
    },
  };

  return (
    <div className="sf-form-field">
      <label htmlFor={id}>
        {label}
        {required ? <span aria-hidden="true"> *</span> : null}
      </label>
      {password ? <AntInput.Password {...sharedProps} /> : <AntInput {...sharedProps} />}
      {error === undefined ? null : <FieldError id={errorId}>{error}</FieldError>}
    </div>
  );
}

export function SelectField({
  id,
  label,
  value,
  options,
  onValueChange,
  onBlur,
  error,
  required = false,
  disabled = false,
  placeholder,
}: SelectFieldProps) {
  const errorId = `${id}-error`;
  return (
    <div className="sf-form-field">
      <label htmlFor={id}>
        {label}
        {required ? <span aria-hidden="true"> *</span> : null}
      </label>
      <AntSelect
        id={id}
        value={value}
        options={[...options]}
        disabled={disabled}
        placeholder={placeholder}
        status={error === undefined ? undefined : 'error'}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={error === undefined ? undefined : errorId}
        aria-required={required}
        onChange={onValueChange}
        onBlur={() => {
          onBlur?.(value ?? '');
        }}
      />
      {error === undefined ? null : <FieldError id={errorId}>{error}</FieldError>}
    </div>
  );
}

export function CheckboxField({
  id,
  label,
  checked,
  onCheckedChange,
  onBlur,
  error,
  disabled = false,
}: CheckboxFieldProps) {
  const errorId = `${id}-error`;
  return (
    <div className="sf-form-field sf-form-checkbox-field">
      <AntCheckbox
        id={id}
        checked={checked}
        disabled={disabled}
        aria-invalid={error === undefined ? undefined : true}
        aria-describedby={error === undefined ? undefined : errorId}
        onChange={(event) => {
          onCheckedChange(event.target.checked);
        }}
        onBlur={(event) => {
          onBlur?.(event.target.checked);
        }}
      >
        {label}
      </AntCheckbox>
      {error === undefined ? null : <FieldError id={errorId}>{error}</FieldError>}
    </div>
  );
}

export function FieldError({ id, children }: FieldErrorProps) {
  return (
    <p className="sf-form-field-error" id={id} role="alert">
      {children}
    </p>
  );
}

export const FormErrorSummary = forwardRef<HTMLDivElement, FormErrorSummaryProps>(
  function FormErrorSummary({ errors, title }, ref) {
    const translate = useFormTranslator();
    if (errors.length === 0) {
      return null;
    }
    return (
      <div className="sf-form-error-summary" ref={ref} role="alert" tabIndex={-1}>
        <strong>{title ?? translate.translate('formProblemSummary')}</strong>
        <ul>
          {errors.map((error, index) => (
            <li key={`${error.fieldId ?? 'form'}-${String(index)}`}>
              {error.fieldId === undefined ? (
                error.message
              ) : (
                <a
                  href={`#${error.fieldId}`}
                  onClick={(event) => {
                    event.preventDefault();
                    document.getElementById(error.fieldId ?? '')?.focus();
                  }}
                >
                  {error.message}
                </a>
              )}
            </li>
          ))}
        </ul>
      </div>
    );
  },
);

export function useFormProblemFocus(): FormProblemFocus {
  const summaryRef = useRef<HTMLDivElement>(null);
  const [focusRequest, setFocusRequest] = useState<{
    readonly fieldId?: string;
    readonly id: number;
  }>();
  const focusFirstProblem = useCallback((firstFieldId?: string) => {
    setFocusRequest((current) => ({ fieldId: firstFieldId, id: (current?.id ?? 0) + 1 }));
  }, []);

  useLayoutEffect(() => {
    if (focusRequest === undefined) {
      return;
    }
    if (focusRequest.fieldId !== undefined) {
      const field = document.getElementById(focusRequest.fieldId);
      if (field instanceof HTMLElement) {
        field.focus();
        return;
      }
    }
    summaryRef.current?.focus();
  }, [focusRequest]);

  return { summaryRef, focusFirstProblem };
}

/**
 * 将关闭、返回和页内导航统一接入放弃确认；浏览器离站时同时启用原生未保存保护。
 */
export function useUnsavedChangesGuard(hasUnsavedChanges: boolean): UnsavedChangesGuard {
  const pendingAction = useRef<(() => void) | null>(null);
  const [confirmationOpen, setConfirmationOpen] = useState(false);

  useEffect(() => {
    if (!hasUnsavedChanges) {
      return undefined;
    }
    const preventUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', preventUnload);
    return () => {
      window.removeEventListener('beforeunload', preventUnload);
    };
  }, [hasUnsavedChanges]);

  const requestDiscard = useCallback(
    (action: () => void) => {
      if (!hasUnsavedChanges) {
        action();
        return;
      }
      pendingAction.current = action;
      setConfirmationOpen(true);
    },
    [hasUnsavedChanges],
  );

  const continueEditing = useCallback(() => {
    pendingAction.current = null;
    setConfirmationOpen(false);
  }, []);

  const discardChanges = useCallback(() => {
    const action = pendingAction.current;
    pendingAction.current = null;
    setConfirmationOpen(false);
    action?.();
  }, []);

  return { confirmationOpen, requestDiscard, continueEditing, discardChanges };
}
