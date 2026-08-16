# saas-forge 安全设计

## 安全原则

安全是核心能力，不是附加功能。租户隔离、身份鉴权、权限控制必须由基础设施提供，不能依赖每一个业务开发者自行正确实现，也不能仅相信调用方传入的 `tenantId`。

## 重点风险

应重点防范：

- Tenant 横向越权、平台与 Tenant 越权、用户横向越权；
- 角色提升与权限绕过；
- Tenant Context 伪造；
- Token、API Key、Client Secret 泄漏；
- 数据隔离条件遗漏与越权导出；
- 已失效 Tenant 持续访问。

## 安全控制方向

### 可信租户上下文

请求必须经 Authentication、Identity、Membership、Tenant Resolve 建立 Tenant Context，再进行 Permission / Feature / Quota 校验和业务处理。上下文须可信、明确、不可随意伪造，并可跨线程、异步和消息场景安全传播。

数据访问应由可信 Tenant Context 驱动，而不是在业务方法中直接使用 `listByTenantId(request.getTenantId())` 作为安全边界。

### 访问控制与权限隔离

通过 Identity、Membership、Role、Permission 的 RBAC 模型实施授权；平台角色与租户角色分离。Feature 校验与 Permission 校验可同时生效，避免仅有操作权限却没有套餐权益的访问。

## 待补充内容

认证机制、Token 生命周期与撤销、密码策略、密钥存储和轮换、传输/静态加密、安全审计留存、接口防护及应急响应流程尚未在原始材料中细化，需要在后续安全详细设计中明确。
