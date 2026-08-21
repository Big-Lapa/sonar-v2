package com.sonar.trading.exchange.binance;

import com.sonar.trading.exchange.binance.http.BinanceFuturesHttpService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Binance signed HTTP client. The client bean is created only when
 * {@code trading.exchange=BINANCE}; PAPER/LIVE(BingX) modes leave it uninstantiated.
 */
@Configuration
@EnableConfigurationProperties(BinanceFuturesProperties.class)
public class BinanceClientConfig {

    @Bean
    @ConditionalOnProperty(name = "trading.exchange", havingValue = "BINANCE")
    public BinanceFuturesClient binanceFuturesClient(BinanceFuturesProperties props) {
        return new BinanceFuturesHttpService(props);
    }
}
