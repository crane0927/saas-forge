import { lazy, Suspense, useEffect, useState } from 'react';

import { PANEL_LABELS, type PrototypePanel, type PrototypeVariant } from './model';

const AntDesignVariant = lazy(async () => {
  const module = await import('./ant-variant');
  return { default: module.AntDesignVariant };
});
const ReactAriaVariant = lazy(async () => {
  const module = await import('./react-aria-variant');
  return { default: module.ReactAriaVariant };
});

// PROTOTYPE：两个候选必须使用相同内容和规则，避免把默认外观差异误判为组件能力差异。
export function DesignSystemFoundationPrototype() {
  const [variant, setVariant] = useState<PrototypeVariant>(readVariant);
  const [panel, setPanel] = useState<PrototypePanel>(readPanel);

  useEffect(() => {
    const handlePopState = () => {
      setVariant(readVariant());
      setPanel(readPanel());
    };
    window.addEventListener('popstate', handlePopState);
    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, []);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return;
      }
      changeVariant(variant === 'antd' ? 'react-aria' : 'antd');
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [variant]);

  const changeVariant = (nextVariant: PrototypeVariant) => {
    updateLocation(nextVariant, panel);
    setVariant(nextVariant);
  };

  const changePanel = (nextPanel: PrototypePanel) => {
    updateLocation(variant, nextPanel);
    setPanel(nextPanel);
  };

  return (
    <div className="prototype-root">
      <Suspense fallback={<p className="prototype-loading">正在加载候选方案…</p>}>
        {variant === 'antd' ? (
          <AntDesignVariant panel={panel} onPanelChange={changePanel} />
        ) : (
          <ReactAriaVariant panel={panel} onPanelChange={changePanel} />
        )}
      </Suspense>

      {!import.meta.env.PROD ? (
        <nav className="prototype-switcher" aria-label="候选组件基础切换">
          <button
            type="button"
            aria-label="切换到上一个候选"
            onClick={() => {
              changeVariant(variant === 'antd' ? 'react-aria' : 'antd');
            }}
          >
            ←
          </button>
          <span aria-live="polite">
            {variant === 'antd' ? 'A · Ant Design' : 'B · React Aria'} · {PANEL_LABELS[panel]}
          </span>
          <button
            type="button"
            aria-label="切换到下一个候选"
            onClick={() => {
              changeVariant(variant === 'antd' ? 'react-aria' : 'antd');
            }}
          >
            →
          </button>
        </nav>
      ) : null}
    </div>
  );
}

function readVariant(): PrototypeVariant {
  return new URLSearchParams(window.location.search).get('variant') === 'react-aria'
    ? 'react-aria'
    : 'antd';
}

function readPanel(): PrototypePanel {
  const panel = new URLSearchParams(window.location.search).get('panel');
  return panel === 'form' || panel === 'danger' ? panel : 'table';
}

function updateLocation(variant: PrototypeVariant, panel: PrototypePanel) {
  const url = new URL(window.location.href);
  url.searchParams.set('variant', variant);
  url.searchParams.set('panel', panel);
  window.history.replaceState(null, '', url);
}
