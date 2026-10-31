package com.sonar.trading.api;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Manual trading panel: list and cancel resting orders on an account — the entry ticket's LIMIT
 * type (fixed in PR #2 review) can now leave an order sitting on the exchange with no way to pull
 * it back short of the exchange's own UI. Read/cancel only, symmetric with
 * {@link PositionOpsController}: no place-order surface here.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/orders")
public class OrderOpsController {

    private static final Logger log = LoggerFactory.getLogger(OrderOpsController.class);

    private final AccountRegistry accountRegistry;
    private final ExecutionAdapterRegistry adapters;

    public OrderOpsController(AccountRegistry accountRegistry, ExecutionAdapterRegistry adapters) {
        this.accountRegistry = accountRegistry;
        this.adapters = adapters;
    }

    public record OrdersResponse(List<AdapterOpenOrder> orders, String error) {}
    public record OpResponse(boolean ok, String message) {}

    @GetMapping
    public OrdersResponse list(@PathVariable String accountId, @RequestParam String symbol) {
        ExecutionAdapter adapter = resolveAdapter(accountId);
        if (adapter == null) {
            return new OrdersResponse(List.of(), "account/venue not found");
        }
        try {
            return new OrdersResponse(adapter.listOpenOrders(symbol), null);
        } catch (RuntimeException e) {
            log.error("[{}] listOpenOrders({}) failed: {}", accountId, symbol, e.toString());
            return new OrdersResponse(List.of(), "could not fetch open orders");
        }
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OpResponse> cancel(@PathVariable String accountId, @PathVariable String orderId,
                                             @RequestParam String symbol) {
        ExecutionAdapter adapter = resolveAdapter(accountId);
        if (adapter == null) {
            return ResponseEntity.badRequest().body(new OpResponse(false, "account/venue not found"));
        }
        try {
            adapter.cancelOrderById(symbol, orderId);
            return ResponseEntity.ok(new OpResponse(true, "order " + orderId + " cancelled"));
        } catch (RuntimeException e) {
            log.error("[{}] cancelOrderById({}, {}) failed: {}", accountId, symbol, orderId, e.toString());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new OpResponse(false, "order cancellation failed"));
        }
    }

    private ExecutionAdapter resolveAdapter(String accountId) {
        return accountRegistry.byId(accountId)
                .flatMap(account -> adapters.forAccount(account.id()))
                .orElse(null);
    }
}
