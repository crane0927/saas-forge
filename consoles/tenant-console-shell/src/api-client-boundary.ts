import type { ConfigurationParameters } from '@saas-forge/api-client';

/**
 * Tenant Console Shell 只通过 API Client 公开入口建立编译契约。
 * 认证、CSRF、Client 实例和产品请求属于后续切片。
 */
export type TenantApiClientBoundary = Pick<ConfigurationParameters, 'basePath'>;
