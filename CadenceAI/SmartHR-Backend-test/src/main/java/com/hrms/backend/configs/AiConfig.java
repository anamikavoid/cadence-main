package com.hrms.backend.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Provides a Clock so the AI services read "now" from one place (and tests can replace it with a fixed clock). */
@Configuration
public class AiConfig {
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
