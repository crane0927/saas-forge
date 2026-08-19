package io.saasforge.tenantaccess.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 该标记只由 Tenant Access 的 Nacos 配置资源提供；缺失时拒绝启动，避免 Nacos 客户端回退为空配置后实例错误就绪。
 */
@Component
public class RequiredNacosConfiguration {

    public RequiredNacosConfiguration(
            @Value("${saasforge.tenant-access.configuration-revision}") String configurationRevision) {
    }
}
