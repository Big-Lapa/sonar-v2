package com.sonar.trading.api;

import com.sonar.trading.execution.PositionOpsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** {@code POST /api/v1/trade/close-all}, {@code /positions/{id}/breakeven}, {@code /positions/{id}/stops}. */
@RestController
public class PositionOpsController {

    private final PositionOpsService service;

    public PositionOpsController(PositionOpsService service) {
        this.service = service;
    }

    public record CloseAllRequest(String account, String symbol) {}
    public record MoveStopRequest(String type, String price) {}
    /** @param type MARKET or LIMIT. @param price required for LIMIT, ignored for MARKET. */
    public record ClosePartialRequest(String type, String qty, String price) {}

    @PostMapping("/api/v1/trade/close-all")
    public ResponseEntity<OpResponse> closeAll(@RequestBody CloseAllRequest req) {
        if (req.account() == null || req.account().isBlank()) {
            return ResponseEntity.badRequest().body(OpResponse.plain(false, "account is required"));
        }
        return respond(service.closeAll(req.account(), req.symbol()));
    }

    @PostMapping("/api/v1/positions/{id}/breakeven")
    public ResponseEntity<OpResponse> breakeven(@PathVariable Long id) {
        return respond(service.breakeven(id));
    }

    @PostMapping("/api/v1/positions/{id}/stops")
    public ResponseEntity<OpResponse> move(@PathVariable Long id, @RequestBody MoveStopRequest req) {
        if (req.type() == null || req.price() == null) {
            return ResponseEntity.badRequest().body(OpResponse.plain(false, "type and price are required"));
        }
        return respond(service.move(id, req.type(), new BigDecimal(req.price())));
    }

    /** Manual trading panel: close some (or all) of a position's quantity, market or limit. */
    @PostMapping("/api/v1/positions/{id}/close")
    public ResponseEntity<OpResponse> closePartial(@PathVariable Long id, @RequestBody ClosePartialRequest req) {
        if (req.type() == null || req.qty() == null) {
            return ResponseEntity.badRequest().body(OpResponse.plain(false, "type and qty are required"));
        }
        BigDecimal qty;
        BigDecimal price;
        try {
            qty = new BigDecimal(req.qty());
            price = req.price() != null && !req.price().isBlank() ? new BigDecimal(req.price()) : null;
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(OpResponse.plain(false, "qty/price: invalid number"));
        }
        return respond(service.closePartial(id, req.type(), qty, price));
    }

    private ResponseEntity<OpResponse> respond(PositionOpsService.OpResult result) {
        return switch (result) {
            case PositionOpsService.OpResult.Ok ok -> ResponseEntity.ok(new OpResponse(true, ok.message(), ok.code(), ok.params()));
            case PositionOpsService.OpResult.Failed failed ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new OpResponse(false, failed.reason(), failed.code(), failed.params()));
        };
    }
}
