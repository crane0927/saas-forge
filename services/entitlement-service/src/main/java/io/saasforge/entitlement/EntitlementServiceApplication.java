package io.saasforge.entitlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@MapperScan("io.saasforge.entitlement.infrastructure.persistence.mapper")
@SpringBootApplication
public class EntitlementServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntitlementServiceApplication.class, args);
    }
}
