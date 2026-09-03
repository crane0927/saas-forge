# Console Locale 是按 Origin 保存的本地 UI 偏好

状态：已确认。

Platform Console 与 Tenant Console 的语言选择只表达当前浏览器界面的显示偏好，不属于 Identity、Tenant 或 IAM 的权威状态。各 Console 按自身 Origin 保存非敏感语言偏好，有效手动偏好优先于浏览器语言；登出、换账号和切换 Tenant 均保留，同 Origin 标签页同步，两个 Console 不跨 Origin 同步。该边界避免将界面选择耦合到账号、Tenant 和认证生命周期，代价是同一用户在不同 Console 或浏览器中需要分别选择语言。

Shell 独占语言选择和偏好写入，向 Remote 提供只读的当前 Locale；切换语言应更新已挂载界面并保留当前操作状态。公共组件、共享 Shell、各 Console 和 Remote 分别拥有独立命名空间的翻译资源，Remote 不能覆盖其他模块资源。语言偏好独立于 [ADR 0039](0039-consoles-share-one-authentication-runtime.md) 所限定的认证持久化状态；增加它不放宽 Access Token、密码、Membership 候选或原始 Problem 等敏感信息的禁存规则。构建期语言扩展、匹配及覆盖范围见 [Console 国际化基线](../29-console-internationalization.md)。
