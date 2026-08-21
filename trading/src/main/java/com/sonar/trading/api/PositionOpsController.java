package com.sonar.trading.api;

import com.sonar.trading.execution.PositionOpsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** 4.8: {@code POST /api/v1/trade/close-all}, {@code /positions/{id}/breakeven}, {@code /positions/{id}/stops}. */
@RestController
public class PositionOpsController {

    private final PositionOpsService service;

    public PositionOpsController(PositionOpsService service) {
        this.service = service;
    }

    public record CloseAllRequest(String account, String symbol) {}
    public record MoveStopRequest(String type, String price) {}
    public record OpResponse(boolean ok, String message) {}

    @PostMapping("/api/v1/trade/close-all")
    public ResponseEntity<OpResponse> closeAll(@RequestBody CloseAllRequest req) {
        if (req.account() == null || req.account().isBlank()) {
            return ResponseEntity.badRequest().body(new OpResponse(false, "account обязателен"));
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
            return ResponseEntity.badRequest().body(new OpResponse(false, "type и price обязательны"));
        }
        return respond(service.move(id, req.type(), new BigDecimal(req.price())));
    }

    private ResponseEntity<OpResponse> respond(PositionOpsService.OpResult result) {
        return switch (result) {
            case PositionOpsService.OpResult.Ok ok -> ResponseEntity.ok(new OpResponse(true, ok.message()));
            case PositionOpsService.OpResult.Failed failed ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(new OpResponse(false, failed.reason()));
        };
    }
}
