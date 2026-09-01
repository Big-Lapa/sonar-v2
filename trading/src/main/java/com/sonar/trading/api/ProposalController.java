package com.sonar.trading.api;

import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.8: {@code POST /api/v1/strategy/proposals} — creates a candidate LONG entry targeted at ONE
 * account and returns its risk preview (qty/riskUsd or a rejection reason). Manual multi-exchange
 * management: {@code accountId} is required — a proposal always executes on exactly the account
 * the caller picked, never a fan-out across every tradable one (that mode existed only for the
 * now-retired LLM auto-search path). Confirming/executing a proposal is stage 3 — not implemented
 * here.
 */
@RestController
@RequestMapping("/api/v1/strategy/proposals")
public class ProposalController {

    private final ProposalRepository proposals;
    private final ProposalPreviewService previewService;

    public ProposalController(ProposalRepository proposals, ProposalPreviewService previewService) {
        this.proposals = proposals;
        this.previewService = previewService;
    }

    public record CreateProposalRequest(
            String accountId, String symbol, String entryPrice, String slPrice, String tpPrice, String rationale) {}

    public record ProposalResponse(String proposalId, List<AccountLegPreview> accounts) {}

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateProposalRequest req) {
        if (req.accountId() == null || req.accountId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accountId обязателен — укажи, на каком аккаунте открывать"));
        }

        String proposalId = UUID.randomUUID().toString();
        ProposalEntity entity = ProposalEntity.pending(proposalId, req.symbol(), "LONG",
                req.entryPrice(), req.slPrice(), req.tpPrice(), req.rationale(),
                System.currentTimeMillis(), req.accountId());
        proposals.save(entity);

        BigDecimal entry = new BigDecimal(req.entryPrice());
        BigDecimal sl = new BigDecimal(req.slPrice());
        List<AccountLegPreview> legs = previewService.preview(req.symbol(), entry, sl, req.accountId());
        return ResponseEntity.ok(new ProposalResponse(proposalId, legs));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProposalEntity> get(@PathVariable String id) {
        return proposals.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Idempotent: PENDING -> REJECTED. Already-decided proposals are left as-is (no error — a
     *  repeat tap on an already-confirmed/rejected proposal is a normal race, not a failure). */
    @PostMapping("/{id}/reject")
    public ResponseEntity<ProposalEntity> reject(@PathVariable String id) {
        ProposalEntity proposal = proposals.findById(id).orElse(null);
        if (proposal == null) {
            return ResponseEntity.notFound().build();
        }
        if ("PENDING".equals(proposal.getStatus())) {
            proposal.setStatus("REJECTED");
            proposal.setDecidedAt(System.currentTimeMillis());
            proposals.save(proposal);
        }
        return ResponseEntity.ok(proposal);
    }
}
