package com.company.labor;

import com.company.labor.foundation.FoundationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(FoundationProperties.class)
public class LaborApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaborApplication.class, args);
    }
}
