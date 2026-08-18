package com.argus.tools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScreeningToolsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScreeningToolsApplication.class, args);
    }
}
