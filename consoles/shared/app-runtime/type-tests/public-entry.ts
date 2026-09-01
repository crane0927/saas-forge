import {
  createAuthenticationRuntimeAfterConfig,
  parseRuntimeConfig,
  type AuthenticationRuntime,
  type IdempotentOperationHandle,
} from '@saas-forge/app-runtime';
import * as appRuntime from '@saas-forge/app-runtime';

declare const runtime: AuthenticationRuntime;
declare const operationHandle: IdempotentOperationHandle;

// @ts-expect-error 未验证 Runtime Config 的底层构造器不属于公共根入口。
void appRuntime.createAuthenticationRuntime;

void runtime.client.getOAuthClient({ clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076' });
void runtime.client.createOAuthClient({
  request: { displayName: 'Runtime Client', allowedScopes: new Set(['runtime:read']) },
  operationHandle,
});
void createAuthenticationRuntimeAfterConfig(
  parseRuntimeConfig({ schemaVersion: 1, apiBaseUrl: 'https://api.example.test' }),
  { realm: globalThis, intent: 'PLATFORM', fetch: globalThis.fetch },
);

// @ts-expect-error 消费者不能读取内存 Access Token。
void runtime.accessToken;
// @ts-expect-error 消费者不能取得携带凭据的通用 Fetch。
void runtime.client.fetch;
void runtime.client.getOAuthClient({
  clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
  // @ts-expect-error 正式 operation 不接受任意 Base URL。
  baseUrl: 'https://attacker.example',
});
void runtime.client.getOAuthClient({
  clientId: '018f1f2e-7b5a-7c42-8c91-2b3d4e5f6076',
  // @ts-expect-error 正式 operation 不接受任意安全请求头。
  headers: { Authorization: 'Bearer attacker-controlled' },
});
