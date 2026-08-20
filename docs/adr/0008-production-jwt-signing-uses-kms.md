# 生产 JWT 由 KMS/HSM 签名

生产环境的 JWT Signing Key 使用 KMS/HSM 托管的不可导出非对称密钥；IAM 只以工作负载身份调用签名接口，不获得或挂载私钥。每个 KMS 密钥版本映射一个唯一 `kid` 并通过 JWKS 发布相应公钥，从而使运行时验签不依赖 KMS，同时缩小私钥泄露范围。

开发与生产统一使用 `RS256`；Gateway、SDK 和业务服务只接受该算法，不能根据 JWT 头部动态信任其他算法。

常规轮换由生产部署的合规策略触发，不将周期写死在代码中：先创建新密钥版本及其唯一 `kid`，在 JWKS 同时发布新、旧公钥，等待 5 分钟缓存窗口后才切换签名。每个 Active Key 记录其签发期间允许使用过的 `maxIssuedTokenTtl`；配置增大时必须先原子提高该值才能按新 TTL 签发，降低配置不回减。旧 Key 转为 `RETIRING` 后，公钥至少保留到 `retiringAt + max(30 分钟, maxIssuedTokenTtl + 30 秒时钟偏差)`，随后才可转为 `RETIRED`、禁用旧版本签名并移出 JWKS。疑似泄露时，立即停止旧版本签名、将其 `kid` 加入验证方每请求检查的撤销集合并移出 JWKS；Gateway、SDK 和业务服务必须拒绝该 `kid`，即使本地仍缓存公钥。开发环境遵循相同的 `kid` 与 JWKS 切换语义，但仅经显式本地操作轮换。
