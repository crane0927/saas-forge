package io.saasforge.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("io.saasforge.iam.infrastructure.persistence.mapper")
@EnableScheduling
@SpringBootApplication
public class IamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamServiceApplication.class, args);
    }
}
