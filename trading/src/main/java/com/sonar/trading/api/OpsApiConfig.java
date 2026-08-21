package com.sonar.trading.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link OpsTokenFilter} on /api/v1/** (11: every write endpoint gated by ADMIN_TOKEN). */
@Configuration
@EnableConfigurationProperties(OpsApiProperties.class)
public class OpsApiConfig {

    @Bean
    public FilterRegistrationBean<OpsTokenFilter> opsTokenFilter(OpsApiProperties props) {
        FilterRegistrationBean<OpsTokenFilter> reg = new FilterRegistrationBean<>(new OpsTokenFilter(props));
        reg.addUrlPatterns("/api/v1/*");
        return reg;
    }
}
