package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.EduCoreApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

class SessionOnlyStartupConfigurationTest {
    @Test
    void excludesOnlyUnusedFrameworkCredentialsWithoutDisablingWebSecurity() {
        var configuration = EduCoreApiApplication.class.getAnnotation(SpringBootApplication.class);
        assertThat(configuration).isNotNull();
        assertThat(configuration.exclude()).containsExactly(UserDetailsServiceAutoConfiguration.class);
        assertThat(configuration.excludeName()).isEmpty();
    }
}
