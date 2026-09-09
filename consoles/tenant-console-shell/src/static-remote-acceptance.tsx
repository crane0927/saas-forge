import { Button } from '@saas-forge/design-system';
import { useEffect, useRef, useState } from 'react';

type Version = 'v1' | 'v2';

/** 仅在开发或显式验收构建中可达，不注册到产品导航，也不创建认证 Runtime。 */
export default function StaticRemoteAcceptance() {
  const [selection, setSelection] = useState<{ version: Version } | null>(null);
  const [status, setStatus] = useState('Select a version');
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (selection === null || container.current === null) return;
    const { version } = selection;
    const target = container.current;
    let active = true;
    const remoteHost = window.location.hostname.replace(/^console\./u, 'remote.');
    const base = `https://${remoteHost}/static-acceptance/${version}/`;
    const stylesheet = document.createElement('link');
    stylesheet.rel = 'stylesheet';
    stylesheet.crossOrigin = 'anonymous';
    stylesheet.href = `${base}styles.css`;
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.alt = `Remote ${version} sample`;
    const styled = new Promise<void>((resolve, reject) => {
      stylesheet.onload = () => {
        resolve();
      };
      stylesheet.onerror = () => {
        reject(new Error('Stylesheet unavailable'));
      };
    });
    document.head.append(stylesheet);
    image.src = `${base}image.svg`;
    // 跨 Origin ES Module 的默认 same-origin 模式不附带目标域凭据；
    // CSS/图片显式 anonymous。这里只读取固定静态资源，不绕过共享业务 HTTP Client。
    void Promise.all([
      import(/* @vite-ignore */ `${base}remote.js`) as Promise<{
        mount: (target: HTMLElement) => void;
      }>,
      styled,
      image.decode(),
    ])
      .then(([module]) => {
        if (!active) return;
        module.mount(target);
        target.append(image);
        setStatus(`${version} ready`);
      })
      .catch(() => {
        if (active) setStatus(`${version} failed; check trusted HTTPS and built artifacts`);
      });
    return () => {
      active = false;
      stylesheet.remove();
      target.replaceChildren();
    };
  }, [selection]);

  return (
    <main>
      <h1>Static Remote acceptance</h1>
      <p>Static delivery only — not a Manifest or business Remote.</p>
      {(['v1', 'v2'] as const).map((candidate) => (
        <Button
          key={candidate}
          disabled={status.endsWith('loading')}
          onClick={() => {
            setStatus(`${candidate} loading`);
            setSelection({ version: candidate });
          }}
        >
          Load {candidate}
        </Button>
      ))}
      <p role="status">{status}</p>
      <div ref={container} />
    </main>
  );
}
