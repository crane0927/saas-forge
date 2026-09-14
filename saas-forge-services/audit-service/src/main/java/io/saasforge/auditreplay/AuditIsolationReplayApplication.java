package io.saasforge.auditreplay;

import io.saasforge.audit.infrastructure.persistence.JdbcAuditIsolationReplayRepository;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement
@EnableAutoConfiguration
@SpringBootConfiguration
@Import({AuditIsolationReplayConfiguration.class, JdbcAuditIsolationReplayRepository.class})
public class AuditIsolationReplayApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AuditIsolationReplayApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.config.name", "audit-isolation-replay"));
        try (ConfigurableApplicationContext ignored = application.run(args)) {
            // ApplicationRunner 完成后立即关闭一次性 Job 上下文。
        }
    }
}
