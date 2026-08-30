class TestResizeObserver implements ResizeObserver {
  disconnect() {}

  observe() {}

  unobserve() {}
}

globalThis.ResizeObserver = TestResizeObserver;

const browserGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element, pseudoElement?: string | null) =>
  browserGetComputedStyle(element, pseudoElement === undefined ? undefined : null);
