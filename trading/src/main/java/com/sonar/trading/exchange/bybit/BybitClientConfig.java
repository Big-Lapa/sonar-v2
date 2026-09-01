package com.sonar.trading.exchange.bybit;

import com.sonar.trading.exchange.bybit.http.BybitFuturesHttpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Bybit signed HTTP client. The client bean is created only when
 * {@code trading.exchange=LIVE}; the default {@code PAPER} mode leaves Phase 1 untouched —
 * same pattern as {@code BingxClientConfig}.
 */
@Configuration
@EnableConfigurationProperties(BybitFuturesProperties.class)
public class BybitClientConfig {

    @Bean
    @ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
    public BybitFuturesClient bybitFuturesClient(BybitFuturesProperties props) {
        return new BybitFuturesHttpService(props);
    }
}
