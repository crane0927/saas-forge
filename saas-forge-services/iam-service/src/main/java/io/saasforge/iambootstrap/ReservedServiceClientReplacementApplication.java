package io.saasforge.iambootstrap;

import io.saasforge.iam.infrastructure.persistence.MyBatisOAuthClientRepository;
import io.saasforge.iam.infrastructure.persistence.MyBatisOutboxEventRepository;
import io.saasforge.iam.infrastructure.persistence.MyBatisReservedServiceClientReplacementRepository;
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
@Import({
        ReservedServiceClientReplacementConfiguration.class,
        MyBatisOAuthClientRepository.class,
        MyBatisReservedServiceClientReplacementRepository.class,
        MyBatisOutboxEventRepository.class
})
public class ReservedServiceClientReplacementApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ReservedServiceClientReplacementApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.config.name", "reserved-service-client-replacement"));
        try (ConfigurableApplicationContext ignored = application.run(args)) {
            // ApplicationRunner 完成后立即关闭一次性 Job 上下文。
        }
    }
}
