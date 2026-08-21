package io.saasforge.iambootstrap;

import io.saasforge.iam.infrastructure.persistence.MyBatisOAuthClientRepository;
import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@MapperScan("io.saasforge.iam.infrastructure.persistence.mapper")
@EnableTransactionManagement
@EnableAutoConfiguration
@SpringBootConfiguration
@Import({ReservedServiceClientBootstrapConfiguration.class, MyBatisOAuthClientRepository.class})
public class ReservedServiceClientBootstrapApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ReservedServiceClientBootstrapApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.config.name", "reserved-service-client-bootstrap"));
        try (ConfigurableApplicationContext ignored = application.run(args)) {
            // ApplicationRunner 完成后立即关闭一次性 Job 上下文。
        }
    }
}
