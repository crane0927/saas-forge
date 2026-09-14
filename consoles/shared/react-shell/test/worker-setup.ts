import { vi } from 'vitest';
// jsdom 不运行 Worker；真实调度及后台时限由 Chrome 产品验收覆盖。
vi.stubGlobal(
  'Worker',
  class {
    onmessage: unknown;
    onerror: unknown;
    postMessage() {}
    terminate() {}
  },
);
