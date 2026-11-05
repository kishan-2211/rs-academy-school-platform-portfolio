package com.educore.api;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.files.FileStorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Authentication uses database-backed sessions, never Boot's generated in-memory user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({ApplicationProperties.class, FileStorageProperties.class})
public class EduCoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(EduCoreApiApplication.class, args);
    }
}
