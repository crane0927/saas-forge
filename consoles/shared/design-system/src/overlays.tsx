import { Button, ConfigProvider, Dropdown, Input, Modal, type ThemeConfig } from 'antd';
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';

import {
  currentFocus,
  removedObjectTargets,
  restoreFocus,
  type RemovedObjectFocusTargets,
  useTopLayerEscape,
} from './overlay-behavior';

export interface ActionMenuItem {
  readonly key: string;
  readonly label: string;
  readonly danger?: boolean;
  readonly disabled?: boolean;
  readonly separatorBefore?: boolean;
}

export interface ActionMenuProps {
  readonly label: string;
  readonly items: readonly ActionMenuItem[];
  readonly onAction: (key: string) => void;
  readonly disabled?: boolean;
}

export interface StandardDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly children: ReactNode;
  readonly onClose: () => void;
  readonly primaryAction?: DialogAction;
  readonly closeLabel?: string;
}

export interface DialogAction {
  readonly label: string;
  readonly onAction: () => void;
  readonly disabled?: boolean;
}

export interface UnsavedChangesDialogProps {
  readonly open: boolean;
  readonly onContinueEditing: () => void;
  readonly onDiscard: () => void;
  readonly description?: string;
}

export interface DangerDialogProps {
  readonly open: boolean;
  readonly title: string;
  readonly objectName: string;
  readonly consequence: string;
  readonly actionLabel: string;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
  readonly removedObjectFocus?: RemovedObjectFocusTargets;
}

export interface IrreversibleDangerDialogProps extends DangerDialogProps {
  readonly confirmationText?: string;
  readonly confirmationLabel?: string;
}

type CloseReason = 'cancel' | 'confirm';

const dialogTheme: ThemeConfig = {
  token: {
    // 立即完成关闭，避免多层弹窗的退出动画把焦点重新拉回已隐藏元素。
    motion: false,
  },
};

interface DialogFrameProps {
  readonly open: boolean;
  readonly title: string;
  readonly children: ReactNode;
  readonly cancelLabel: string;
  readonly confirmAction?: DialogAction;
  readonly confirmDanger?: boolean;
  readonly onCancel: () => void;
  readonly removedObjectFocus?: RemovedObjectFocusTargets;
}

export function ActionMenu({ label, items, onAction, disabled = false }: ActionMenuProps) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  const closeMenu = () => {
    setOpen(false);
    restoreFocus([triggerRef.current]);
  };

  useTopLayerEscape(open, closeMenu);

  return (
    <Dropdown
      open={open}
      trigger={['click']}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen);
        if (!nextOpen) {
          restoreFocus([triggerRef.current]);
        }
      }}
      menu={{
        items: items.flatMap((item) => [
          ...(item.separatorBefore ? ([{ type: 'divider' as const }] as const) : []),
          {
            key: item.key,
            label: item.label,
            danger: item.danger,
            disabled: item.disabled,
          },
        ]),
        onClick: ({ key }) => {
          setOpen(false);
          onAction(key);
          restoreFocus([triggerRef.current]);
        },
      }}
    >
      <Button ref={triggerRef} disabled={disabled} aria-expanded={open} aria-haspopup="menu">
        {label}
      </Button>
    </Dropdown>
  );
}

export function StandardDialog({
  open,
  title,
  children,
  onClose,
  primaryAction,
  closeLabel = '关闭',
}: StandardDialogProps) {
  return (
    <DialogFrame
      open={open}
      title={title}
      cancelLabel={closeLabel}
      onCancel={onClose}
      confirmAction={primaryAction}
    >
      {children}
    </DialogFrame>
  );
}

export function UnsavedChangesDialog({
  open,
  onContinueEditing,
  onDiscard,
  description = '离开后，本次未保存的修改不会保留。',
}: UnsavedChangesDialogProps) {
  return (
    <DialogFrame
      open={open}
      title="放弃未保存的修改？"
      cancelLabel="继续编辑"
      onCancel={onContinueEditing}
      confirmAction={{ label: '放弃修改', onAction: onDiscard }}
      confirmDanger
    >
      <p>{description}</p>
    </DialogFrame>
  );
}

export function RecoverableDangerDialog({
  open,
  title,
  objectName,
  consequence,
  actionLabel,
  onCancel,
  onConfirm,
  removedObjectFocus,
}: DangerDialogProps) {
  return (
    <DialogFrame
      open={open}
      title={title}
      cancelLabel="取消"
      onCancel={onCancel}
      confirmAction={{ label: actionLabel, onAction: onConfirm }}
      confirmDanger
      removedObjectFocus={removedObjectFocus}
    >
      <p>
        操作对象：<strong>{objectName}</strong>
      </p>
      <p>{consequence}</p>
    </DialogFrame>
  );
}

export function IrreversibleDangerDialog({
  open,
  title,
  objectName,
  consequence,
  actionLabel,
  onCancel,
  onConfirm,
  removedObjectFocus,
  confirmationText = objectName,
  confirmationLabel = '输入对象名称确认',
}: IrreversibleDangerDialogProps) {
  const [confirmation, setConfirmation] = useState('');
  const previousOpen = useRef(false);

  useLayoutEffect(() => {
    if (open && !previousOpen.current) {
      setConfirmation('');
    }
    previousOpen.current = open;
  }, [open]);

  return (
    <DialogFrame
      open={open}
      title={title}
      cancelLabel="取消"
      onCancel={onCancel}
      confirmAction={{
        label: actionLabel,
        onAction: onConfirm,
        disabled: confirmation !== confirmationText,
      }}
      confirmDanger
      removedObjectFocus={removedObjectFocus}
    >
      <p>
        操作对象：<strong>{objectName}</strong>
      </p>
      <p>{consequence}</p>
      <p>
        请输入 <strong>{confirmationText}</strong> 后继续。
      </p>
      <label className="sf-dialog-field">
        <span>{confirmationLabel}</span>
        <Input
          value={confirmation}
          onChange={(event) => {
            setConfirmation(event.target.value);
          }}
        />
      </label>
    </DialogFrame>
  );
}

function DialogFrame({
  open,
  title,
  children,
  cancelLabel,
  confirmAction,
  confirmDanger = false,
  onCancel,
  removedObjectFocus,
}: DialogFrameProps) {
  const cancelButtonRef = useRef<HTMLButtonElement>(null);
  const originRef = useRef<HTMLElement | null>(null);
  const removalFocusRef = useRef<RemovedObjectFocusTargets | undefined>(undefined);
  const closeReasonRef = useRef<CloseReason>('cancel');
  const previousOpen = useRef(false);

  useLayoutEffect(() => {
    if (open && !previousOpen.current) {
      originRef.current = currentFocus();
      removalFocusRef.current = removedObjectFocus;
      closeReasonRef.current = 'cancel';
      restoreFocus([() => cancelButtonRef.current]);
    } else if (!open && previousOpen.current) {
      const removalTargets =
        closeReasonRef.current === 'confirm' ? removedObjectTargets(removalFocusRef.current) : [];
      restoreFocus([...removalTargets, originRef.current]);
    }
    previousOpen.current = open;
  }, [open]);

  const cancel = () => {
    closeReasonRef.current = 'cancel';
    onCancel();
  };

  useTopLayerEscape(open, cancel);

  const restoreDialogFocus = () => {
    const removalTargets =
      closeReasonRef.current === 'confirm' ? removedObjectTargets(removalFocusRef.current) : [];
    restoreFocus([...removalTargets, originRef.current]);
  };

  return (
    <ConfigProvider theme={dialogTheme}>
      <Modal
        open={open}
        title={title}
        keyboard={false}
        closable={false}
        mask={{ closable: false }}
        focusable={{ focusTriggerAfterClose: false }}
        onCancel={cancel}
        afterOpenChange={(nextOpen) => {
          if (nextOpen) {
            cancelButtonRef.current?.focus();
          } else {
            restoreDialogFocus();
          }
        }}
        footer={
          <>
            <Button ref={cancelButtonRef} onClick={cancel}>
              {cancelLabel}
            </Button>
            {confirmAction === undefined ? null : (
              <Button
                type="primary"
                danger={confirmDanger}
                disabled={confirmAction.disabled}
                onClick={() => {
                  closeReasonRef.current = 'confirm';
                  confirmAction.onAction();
                }}
              >
                {confirmAction.label}
              </Button>
            )}
          </>
        }
      >
        {children}
      </Modal>
    </ConfigProvider>
  );
}
