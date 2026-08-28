package io.saasforge.audit.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {
    @Bean
    Clock auditClock() {
        return Clock.systemUTC();
    }
}
