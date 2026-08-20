# 认证 Token 轮换采用重试感知语义

登录和登出不使用通用的 HTTP 幂等记录：每次成功登录建立独立的 Refresh Token Family，重复登出始终清除 Cookie 并返回成功。刷新保留 `Idempotency-Key`，并先按旧 Token 摘要取得绑定该键、默认 5 秒的 Refresh Rotation Lease；Lease 存续期间的不同键请求以 `409` 拒绝但不撤销 Family，避免把多标签页的重叠刷新误判为 Token 窃取。首次消费后的 10 秒内，同一旧 Refresh Token 以同一幂等键最多恢复一次，IAM 废止客户端可能未收到的后继 Refresh Token、撤销同一响应签发的 Access Token `jti`，再签发替代 Token；Lease 到期后的不同键、再次同键重试、超过恢复窗口或其他已消费 Token 的重放仍撤销整个 Family。恢复窗口是默认 10 秒、硬上限 30 秒的安全策略配置。认证端点不保存或原样重放 Token 响应，从而在保持 Refresh Token 仅存摘要的前提下兼顾网络恢复、并发抑制与重放检测。
