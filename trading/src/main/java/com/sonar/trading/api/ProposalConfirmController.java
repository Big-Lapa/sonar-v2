package com.sonar.trading.api;

import com.sonar.trading.execution.ExecutionLegResult;
import com.sonar.trading.execution.ProposalExecutionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 4.8: {@code POST /api/v1/strategy/proposals/{id}/confirm} — executes 4.6 across every tradable
 * BingX account. Idempotent: a repeated tap on an already-CONFIRMED proposal returns the
 * original report instead of placing a second order.
 */
@RestController
@RequestMapping("/api/v1/strategy/proposals")
public class ProposalConfirmController {

    private final ProposalExecutionService executionService;

    public ProposalConfirmController(ProposalExecutionService executionService) {
        this.executionService = executionService;
    }

    public record ConfirmResponse(String proposalId, String status, List<ExecutionLegResult> accounts,
                                  String abortReason) {}

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@PathVariable String id) {
        var outcome = executionService.confirm(id);
        return switch (outcome) {
            case ProposalExecutionService.ConfirmOutcome.NotFound ignored ->
                    ResponseEntity.notFound().build();
            case ProposalExecutionService.ConfirmOutcome.Aborted aborted ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ConfirmResponse(id, "ABORTED", List.of(), aborted.reason()));
            case ProposalExecutionService.ConfirmOutcome.AlreadyProcessed already ->
                    ResponseEntity.ok(new ConfirmResponse(id, "ALREADY_PROCESSED", already.legs(), null));
            case ProposalExecutionService.ConfirmOutcome.Executed executed ->
                    ResponseEntity.ok(new ConfirmResponse(id, "EXECUTED", executed.legs(), null));
        };
    }
}
