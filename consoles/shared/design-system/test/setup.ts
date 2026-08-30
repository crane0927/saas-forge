class TestResizeObserver implements ResizeObserver {
  disconnect() {}

  observe() {}

  unobserve() {}
}

globalThis.ResizeObserver = TestResizeObserver;

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

const browserGetComputedStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element, pseudoElement?: string | null) =>
  browserGetComputedStyle(element, pseudoElement === undefined ? undefined : null);
