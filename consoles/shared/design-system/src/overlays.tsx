import { Button, ConfigProvider, Dropdown, Input, Modal, type ThemeConfig } from 'antd';
import { createTranslator, defineMessages } from '@saas-forge/i18n';
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';

import enUS from './messages/overlays/en-US.json';
import zhCN from './messages/overlays/zh-CN.json';
import {
  currentFocus,
  removedObjectTargets,
  restoreFocus,
  type RemovedObjectFocusTargets,
  useTopLayerEscape,
} from './overlay-behavior';
import { useDesignSystemLocale } from './theme-provider';

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

const overlayMessages = defineMessages({
  'en-US': enUS,
  'zh-CN': zhCN,
});

function useOverlayTranslator() {
  return createTranslator({
    namespace: '@saas-forge/design-system/overlays',
    locale: useDesignSystemLocale(),
    messages: overlayMessages,
  });
}

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
  closeLabel,
}: StandardDialogProps) {
  const translate = useOverlayTranslator();
  return (
    <DialogFrame
      open={open}
      title={title}
      cancelLabel={closeLabel ?? translate.translate('dialogClose')}
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
  description,
}: UnsavedChangesDialogProps) {
  const translate = useOverlayTranslator();
  return (
    <DialogFrame
      open={open}
      title={translate.translate('dialogUnsavedTitle')}
      cancelLabel={translate.translate('dialogUnsavedContinue')}
      onCancel={onContinueEditing}
      confirmAction={{ label: translate.translate('dialogUnsavedDiscard'), onAction: onDiscard }}
      confirmDanger
    >
      <p>{description ?? translate.translate('dialogUnsavedDescription')}</p>
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
  const translate = useOverlayTranslator();
  return (
    <DialogFrame
      open={open}
      title={title}
      cancelLabel={translate.translate('dialogCancel')}
      onCancel={onCancel}
      confirmAction={{ label: actionLabel, onAction: onConfirm }}
      confirmDanger
      removedObjectFocus={removedObjectFocus}
    >
      <p>
        {translate.translate('dialogObjectLabel')} <strong>{objectName}</strong>
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
  confirmationLabel,
}: IrreversibleDangerDialogProps) {
  const translate = useOverlayTranslator();
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
      cancelLabel={translate.translate('dialogCancel')}
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
        {translate.translate('dialogObjectLabel')} <strong>{objectName}</strong>
      </p>
      <p>{consequence}</p>
      <p>{translate.translate('dialogConfirmationInstruction', { confirmationText })}</p>
      <label className="sf-dialog-field">
        <span>{confirmationLabel ?? translate.translate('dialogConfirmationLabel')}</span>
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
