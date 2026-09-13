import axe from 'axe-core';

/** 消费者浏览器夹具复用 Design System 已管理的无障碍检查依赖。 */
export function auditAccessibility(element: HTMLElement) {
  return axe.run(element, { rules: { region: { enabled: false } } });
}
