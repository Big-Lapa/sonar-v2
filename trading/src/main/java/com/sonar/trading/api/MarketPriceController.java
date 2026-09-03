package com.sonar.trading.api;

import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trading panel: a live reference price wasn't exposed anywhere outside
 * {@code ProposalExecutionService.checkPriceDrift} (internal use only) — the panel needs one to
 * pre-fill the entry price / size a manual notional before a proposal even exists yet.
 */
@RestController
@RequestMapping("/api/v1/market")
public class MarketPriceController {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceController.class);

    private final ExecutionAdapterRegistry adapters;

    public MarketPriceController(ExecutionAdapterRegistry adapters) {
        this.adapters = adapters;
    }

    public record PriceResponse(String symbol, String exchange, String price, String error) {}

    @GetMapping("/price")
    public PriceResponse price(@RequestParam String symbol, @RequestParam String exchange) {
        ExecutionAdapter adapter = adapters.forExchange(exchange).orElse(null);
        if (adapter == null) {
            return new PriceResponse(symbol, exchange, null, "venue '" + exchange + "' не подключена");
        }
        try {
            var price = adapter.getMarkPrice(symbol);
            if (price == null) {
                return new PriceResponse(symbol, exchange, null, "цена недоступна");
            }
            return new PriceResponse(symbol, exchange, price.toPlainString(), null);
        } catch (RuntimeException e) {
            log.warn("[{}] getMarkPrice({}) failed: {}", exchange, symbol, e.toString());
            return new PriceResponse(symbol, exchange, null, e.getMessage());
        }
    }
}
