package io.saasforge.tenantaccess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("io.saasforge.tenantaccess.infrastructure.persistence.mapper")
@EnableScheduling
@SpringBootApplication
public class TenantAccessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantAccessServiceApplication.class, args);
    }
}
