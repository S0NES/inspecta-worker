package br.com.vistoria.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"br.com.vistoria.worker", "br.com.vistoria.core"})
@EntityScan("br.com.vistoria.core")
@EnableJpaRepositories(basePackages = "br.com.vistoria.core", considerNestedRepositories = true)
@EnableScheduling
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
