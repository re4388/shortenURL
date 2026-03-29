package com.example.shorturl.config;

import com.example.shorturl.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Apply IP Rate Limiting to Create and Redirect endpoints
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/urls/**")
                .addPathPatterns("/*");
    }
}
