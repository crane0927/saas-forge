import { useEffect, useLayoutEffect, useRef } from 'react';

export type FocusTarget = HTMLElement | null | (() => HTMLElement | null);

export interface RemovedObjectFocusTargets {
  readonly nextRow?: FocusTarget;
  readonly tableHeading?: FocusTarget;
  readonly emptyState?: FocusTarget;
}

interface EscapeLayer {
  readonly id: symbol;
  readonly cancel: () => void;
}

const escapeLayers: EscapeLayer[] = [];

function cancelTopLayer(event: KeyboardEvent) {
  if (event.key !== 'Escape') {
    return;
  }

  const topLayer = escapeLayers.at(-1);
  if (topLayer === undefined) {
    return;
  }

  event.preventDefault();
  event.stopImmediatePropagation();
  topLayer.cancel();
}

function registerEscapeLayer(layer: EscapeLayer) {
  if (escapeLayers.length === 0) {
    document.addEventListener('keydown', cancelTopLayer, true);
  }
  escapeLayers.push(layer);

  return () => {
    const index = escapeLayers.findIndex((candidate) => candidate.id === layer.id);
    if (index >= 0) {
      escapeLayers.splice(index, 1);
    }
    if (escapeLayers.length === 0) {
      document.removeEventListener('keydown', cancelTopLayer, true);
    }
  };
}

export function useTopLayerEscape(open: boolean, onCancel: () => void) {
  const cancelRef = useRef(onCancel);

  useLayoutEffect(() => {
    cancelRef.current = onCancel;
  }, [onCancel]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const id = Symbol('design-system-overlay');
    return registerEscapeLayer({
      id,
      cancel: () => {
        cancelRef.current();
      },
    });
  }, [open]);
}

export function currentFocus(): HTMLElement | null {
  return document.activeElement instanceof HTMLElement ? document.activeElement : null;
}

export function restoreFocus(targets: readonly FocusTarget[]) {
  // 连续两帧让 Portal 卸载和触发方列表重排先完成，再选择仍连接的后继焦点。
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      for (const target of targets) {
        const element = typeof target === 'function' ? target() : target;
        if (element?.isConnected === true) {
          element.focus();
          return;
        }
      }
    });
  });
}

export function removedObjectTargets(
  targets: RemovedObjectFocusTargets | undefined,
): readonly FocusTarget[] {
  if (targets === undefined) {
    return [];
  }
  return [targets.nextRow, targets.tableHeading, targets.emptyState].filter(
    (target): target is FocusTarget => target !== undefined,
  );
}
