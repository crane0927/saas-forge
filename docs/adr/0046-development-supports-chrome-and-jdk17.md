# 开发阶段仅支持 Chrome 与 JDK 17

当前阶段优先减少兼容适配与重复验收的维护负担，产品仅支持桌面版 Google Chrome 当前稳定版和 JDK 17，暂不承担旧版或移动端 Chrome、Edge、Firefox、Safari、其他 Chromium 浏览器及 JDK 21 的适配要求。Chromium 沿用现有日常功能与视觉测试基线，作为测试工具而非独立产品支持目标；必要的真实产品验收统一使用 Chrome。

## Consequences

- 本地、CI、发布与未完成 Issue/PRD 的兼容矩阵按此收缩；覆盖 ADR 0044 中保留既有浏览器/JDK 矩阵的范围要求，历史验收证据不改写为新范围的通过记录。
- 功能、键盘、无障碍、国际化、视觉、四域 TLS/Cookie/CORS/CSRF、契约基线、迁移、Fresh Compose 与失败传播要求仍有效；删除 JDK 21 任务时将其契约基线保护迁到 JDK 17 任务。
- 首次对外发布前重新评估支持范围，新增浏览器或 JDK 须明确确认，不自动恢复旧矩阵。收缩范围不构成耗时改善的实测结论。
