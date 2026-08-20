package io.saasforge.tenantaccess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@MapperScan("io.saasforge.tenantaccess.infrastructure.persistence.mapper")
@SpringBootApplication
public class TenantAccessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantAccessServiceApplication.class, args);
    }
}
