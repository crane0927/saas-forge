# IAM 持久化最小 Access Token Issuance 元数据

User Access Token 仍由 Gateway、SDK 和业务服务使用 JWKS 本地验签，但 IAM 为每次签发保存不含 JWT、签名、邮箱、角色或权限的最小 Access Token Issuance。该索引把 `jti` 关联到 Family、Identity、可选 Membership/Tenant、`kid` 与有效期，使 Refresh Token 重放、Family 撤销、成员禁用或 Tenant 冻结能够找到并立即撤销全部尚未到期的 JWT；普通请求不查询该表，因此不把本地验签退化为集中式 Token introspection。
