package com.storyforge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@MapperScan("com.storyforge")
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StoryForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoryForgeApplication.class, args);
    }
}
