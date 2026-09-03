# 变更记录

本项目遵循语义化版本。尚未发布稳定版本。

## Unreleased

### Added

- 初始化 Maven 多模块项目骨架。

### Changed

#### 浏览器认证 v1 破坏性迁移

- 现有认证路径保持不变，`POST /api/v1/auth/refresh` 与 `POST /api/v1/auth/logout` 的 JSON Body 新增必填 `sessionSlot: PLATFORM | TENANT`；登录继续使用 `contextType` 表达意图，两个官方 Console 显式提交宿主固定值。
- Refresh Cookie 拆分为 API Origin 签发的 `__Host-sf_platform_refresh` 与 `__Host-sf_tenant_refresh`，分别承载 Platform 与 Tenant 槽位；均为 host-only、Secure、HttpOnly、SameSite=Strict、Path=/，不设置 Domain。刷新和登出只操作所选槽位。
- `platform.<root>` 只能提交 Platform Intent/Slot，`console.<root>` 只能提交 Tenant Intent/Slot；浏览器请求须满足受控 Origin、JSON Content-Type、`X-SF-CSRF: 1` 与 Fetch Metadata 校验。Origin、Cookie 和 Fetch Metadata 仍由浏览器管理，调用方不得伪造。
- 旧 `__Host-sf_refresh` Cookie 将被清除，旧 Refresh Token Family 不迁移，也不并行支持旧单槽位协议；原有浏览器会话需要重新登录。
- **外部消费者中断风险**：依赖旧 Cookie、缺少 `sessionSlot` 或不符合来源与槽位配对要求的现有 v1 客户端可能无法继续认证、刷新或登出。外部消费者须同步升级生成 Client 和调用协议，不能将本次变更视为向后兼容。Gateway、IAM、生成 Client、两个 Console、E2E 脚本与全部第一方消费者须原子升级。
- 本次仅适用 [ADR 0038](docs/adr/0038-browser-sessions-use-intent-bound-slots.md) 批准的浏览器认证 v1 例外；历史兼容基线保持不变，其他 v1 契约不获得豁免。协议与迁移要求见 [Console 认证 Runtime 与浏览器会话规格](docs/28-console-authentication-runtime.md)。
