package io.saasforge.iam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 该标记由受控 Nacos 资源或本地个人配置提供；缺失时拒绝启动，避免空配置实例错误就绪。
 */
@Component
public class RequiredNacosConfiguration {

    public RequiredNacosConfiguration(@Value("${saasforge.iam.configuration-revision}") String configurationRevision) {
    }
}
