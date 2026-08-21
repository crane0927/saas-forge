# 系统创建默认 Platform Admin

首次部署时，系统直接创建一个全局 `Identity` 并授予 Platform 角色，不创建或伪造 Tenant `Membership`。管理员邮箱和随机初始密码只由外部密钥管理系统向首次部署注入；IAM 仅保存密码的 Argon2id 哈希，且首次登录必须修改密码并撤销该初始凭据建立的会话。这样避免了公网首注、代码或镜像中的默认密码，同时保持 Platform 与 Tenant 授权边界分离。

初始凭据自创建起仅有效 24 小时，期间只能建立完成改密所需的受限会话，不能调用 Platform 管理接口。成功改密后初始凭据永久失效；过期或疑似泄露时，只能由部署侧受限凭据执行可审计的重置并生成新的随机初始密码。

首次创建由 IAM 同一制品提供的显式 bootstrap 模式执行，并作为 Flyway 完成后的部署前置一次性 Job 运行；正常 IAM 启动不读取 bootstrap Secret，也不自动创建或重置管理员。Job 只从外部挂载的 Secret 文件读取邮箱和随机初始密码，并在 IAM 单一事务中创建 Identity、24 小时 Initial Platform Credential 与 `PLATFORM_ADMIN` Role Assignment。相同管理员已处于预期状态时重复执行可幂等成功；Identity、Role Assignment 或 Credential 状态与请求不一致时必须失败并转人工处理，不能覆盖既有状态。初始凭据重置使用独立的受限 bootstrap 操作，不复用首次创建逻辑，也不提供公网初始化 API 或通过 Flyway 写入环境数据。

受限 reset 操作从外部挂载文件读取新随机密码与 UUIDv7 `resetRequestId`，且只允许尚无有效普通 Password Credential 的 Default Platform Admin。它在 IAM 单一事务中永久失效全部旧 Initial Platform Credential、撤销对应的全部 Initial Credential Session，并创建新的 24 小时初始凭据；同一 `resetRequestId` 幂等重放，新一次重置必须使用新 ID。已有有效普通 Password Credential 时操作失败并要求使用正式账号恢复流程，部署入口不得成为常规密码重置后门；操作不返回或记录明文密码，也不提供 HTTP API。
