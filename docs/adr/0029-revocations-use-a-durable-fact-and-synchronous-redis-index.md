# 撤销使用持久化事实与同步 Redis Index

IAM 将 PostgreSQL 中的 Access Token 和 Signing Key 撤销状态作为持久化事实，并以 Redis Revocation Index 为 Gateway 与 Starter 的请求热路径提供即时拒绝。撤销先通过 Spring Data Redis 与 Lua 原子写入 Redis，再提交数据库，允许提交失败时产生额外拒绝但不允许数据库已撤销而 Redis 尚未拒绝；Redis 恢复时必须从数据库重建并在完成前保持 Index 未就绪，验证方对未就绪状态 fail-closed。Spring Security/Nimbus 负责 JWT/JWKS，Spring Data Redis 负责连接、TTL 与脚本，但项目保留一个小型应用层协调器表达这一安全顺序；不引入 Spring Authorization Server、XA 或异步 CDC，因为它们不能在保持本地 JWT 验签的同时替代该一致性语义。
