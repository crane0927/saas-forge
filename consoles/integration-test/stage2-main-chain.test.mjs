import test from 'node:test';
import { runStage2MainChain } from './stage2-main-chain.mjs';

// 后续切片在同一回调内使用内存中的会话与资源，不能从旧证据加载业务前置。
test('同轮中文 Console 身份、Tenant 主链与 Audit 关联', { timeout: 240_000 }, async () => {
  await runStage2MainChain();
});
