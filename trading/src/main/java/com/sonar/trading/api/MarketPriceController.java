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

    public record PriceResponse(String symbol, String account, String price, String error) {}

    /** Quoted per <b>account</b>, not per venue: two accounts on the same exchange can sit on
     *  different domains (BingX VST demo vs production), so each one is asked on its own client. */
    @GetMapping("/price")
    public PriceResponse price(@RequestParam String symbol, @RequestParam String account) {
        ExecutionAdapter adapter = adapters.forAccount(account).orElse(null);
        if (adapter == null) {
            return new PriceResponse(symbol, account, null, "аккаунт '" + account + "' не подключён");
        }
        try {
            var price = adapter.getMarkPrice(symbol);
            if (price == null) {
                return new PriceResponse(symbol, account, null, "цена недоступна");
            }
            return new PriceResponse(symbol, account, price.toPlainString(), null);
        } catch (RuntimeException e) {
            log.warn("[{}] getMarkPrice({}) failed: {}", account, symbol, e.toString());
            return new PriceResponse(symbol, account, null, "цена недоступна");
        }
    }
}
