// Worker 只调度检查，不接触认证状态、网络请求或任何凭据。
let deadline: ReturnType<typeof setTimeout> | undefined;
let activeSequence: number | undefined;
setInterval(() => {
  self.postMessage({ type: 'tick' });
}, 20_000);
self.onmessage = (event: MessageEvent<{ type: 'start' | 'finish'; sequence: number }>) => {
  if (event.data.type === 'start') {
    clearTimeout(deadline);
    const sequence = event.data.sequence;
    activeSequence = sequence;
    deadline = setTimeout(() => {
      self.postMessage({ type: 'deadline', sequence });
    }, 5_000);
  } else if (event.data.sequence === activeSequence) {
    clearTimeout(deadline);
    activeSequence = undefined;
  }
};
export {};
