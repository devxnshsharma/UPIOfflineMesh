package com.demo.upimesh.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MeshProperties.class)
public class AppConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;

    public AppConfig(AdminInterceptor adminInterceptor) {
        this.adminInterceptor = adminInterceptor;
    }

    /** Injected everywhere "now" matters so tests can control time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor).addPathPatterns("/api/**");
    }
}
