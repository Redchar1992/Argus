package com.storyforge;

import org.mybatis.spring.annotation.MapperScan;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@MapperScan(basePackages = "com.storyforge", annotationClass = Mapper.class)
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StoryForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoryForgeApplication.class, args);
    }
}
