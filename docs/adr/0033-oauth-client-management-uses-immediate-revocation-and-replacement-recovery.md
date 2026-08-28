# OAuth Client 管理采用即时吊销与替代式 Secret 恢复

OAuth Client 管理只接受 Platform Admin 的 Platform User Access Token，公开创建只产生 `RUNTIME_SERVICE`，三个 `RESERVED_SERVICE` 仍由部署流程按固定服务身份和 Scope 管理。Client 的名称、类型与 Scope 创建后不可变；MVP 只增加单 Client 详情、创建、Secret 轮换、Secret 签发恢复和不可逆吊销，不提供列表、修改、恢复 Client 或单独吊销某把 Secret。

Client 吊销必须立即阻止新 Token 签发和全部已签发 Service Access Token，而不是等待五分钟自然过期。PostgreSQL 保存不可逆吊销事实，IAM 将 `client_id` 以无 TTL Key 同步投影到现有 Redis Revocation Index；签发方与全部验证方复用同一 Ready 边界并失败关闭。按 Client 拒绝避免为每枚 Service Access Token 建立持久 Issuance 索引，同时保留本地 JWT 验签。

IAM 继续只保存 Client Secret 的 SHA-256 摘要，不保存可重放的明文或密文响应。常规轮换固定重叠 24 小时；首次创建或轮换响应遗失时，原操作者只能在十分钟内创建一次显式 Secret Issuance Recovery，使上一把未送达 Secret 失效并只展示一次替代 Secret。Secret 签发操作因此成为通用 24 小时幂等留存和响应重放规则的窄安全例外：非敏感操作记录永久保留，但成功响应不重放 Secret。

已吊销的保留 Client 不能复活，只能由严格幂等的部署侧 Replacement Job 以新 Client ID 和固定 Scope 替代。Client 创建、Secret 轮换、Client 吊销和签发恢复均在本地事务中写入不含凭据的 Committed Fact Event；事件如实区分用户 Identity 与 Deployment Operation。完整接口、数据、Redis、事件、迁移和验收规格见 [OAuth 2.0 Client Credentials 管理规格](../22-oauth-client-credentials-management.md)。
