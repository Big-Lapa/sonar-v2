package com.sonar.trading.exchange.bingx;

import com.sonar.trading.exchange.bingx.http.BingxFuturesHttpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the BingX signed HTTP client. The client bean is created only when
 * {@code trading.exchange=LIVE}; the default {@code PAPER} mode leaves Phase 1 untouched.
 */
@Configuration
@EnableConfigurationProperties(BingxFuturesProperties.class)
public class BingxClientConfig {

    @Bean
    @ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
    public BingxFuturesClient bingxFuturesClient(BingxFuturesProperties props) {
        return new BingxFuturesHttpService(props);
    }
}
