import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

import { Button, DesignIcon } from './foundation';

export interface FeedbackAction {
  readonly label: string;
  readonly onAction: () => void;
}

export interface SuccessFeedbackProps {
  readonly message: string;
  /** 文案翻译变化不代表新的反馈事件；传入稳定标识可保留原有自动关闭时机。 */
  readonly stableKey?: string;
  readonly durationMs?: number;
  readonly onDismiss?: () => void;
}

export interface WarningFeedbackProps {
  readonly title: string;
  readonly children?: ReactNode;
}

export interface PersistentErrorProps {
  readonly title: string;
  readonly children?: ReactNode;
  readonly action?: FeedbackAction;
  readonly onClose?: () => void;
}

export function SuccessFeedback({
  message,
  stableKey,
  durationMs = 3000,
  onDismiss,
}: SuccessFeedbackProps) {
  const [visible, setVisible] = useState(true);
  const dismissRef = useRef(onDismiss);
  dismissRef.current = onDismiss;

  useEffect(() => {
    setVisible(true);
    const timer = window.setTimeout(() => {
      setVisible(false);
      dismissRef.current?.();
    }, durationMs);
    return () => {
      window.clearTimeout(timer);
    };
  }, [durationMs, stableKey ?? message]);

  return visible ? (
    <div className="sf-feedback sf-feedback-success" role="status">
      <DesignIcon name="check" />
      <span>{message}</span>
    </div>
  ) : null;
}

export function WarningFeedback({ title, children }: WarningFeedbackProps) {
  const titleId = useId();
  return (
    <section className="sf-feedback sf-feedback-warning" aria-labelledby={titleId}>
      <DesignIcon name="warning" />
      <div>
        <strong id={titleId}>{title}</strong>
        {children === undefined ? null : <div>{children}</div>}
      </div>
    </section>
  );
}

export function PersistentError({ title, children, action, onClose }: PersistentErrorProps) {
  return (
    <section className="sf-feedback sf-feedback-error" role="alert">
      <DesignIcon name="error" />
      <div className="sf-feedback-body">
        <strong>{title}</strong>
        {children === undefined ? null : <div>{children}</div>}
      </div>
      {action === undefined ? null : (
        <Button variant="text" onClick={action.onAction}>
          {action.label}
        </Button>
      )}
      {onClose === undefined ? null : (
        <Button variant="text" onClick={onClose}>
          关闭错误提示
        </Button>
      )}
    </section>
  );
}
