package com.sonar.trading.api;

import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 4.8: {@code POST /api/v1/strategy/proposals} — creates a candidate LONG entry and returns the
 * per-account risk preview (qty/riskUsd or a rejection reason) shown in the confirm message.
 * Confirming/executing a proposal is stage 3 — not implemented here.
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
            String symbol, String entryPrice, String slPrice, String tpPrice, String rationale) {}

    public record ProposalResponse(String proposalId, List<AccountLegPreview> accounts) {}

    @PostMapping
    public ProposalResponse create(@RequestBody CreateProposalRequest req) {
        String proposalId = UUID.randomUUID().toString();
        ProposalEntity entity = ProposalEntity.pending(proposalId, req.symbol(), "LONG",
                req.entryPrice(), req.slPrice(), req.tpPrice(), req.rationale(),
                System.currentTimeMillis());
        proposals.save(entity);

        BigDecimal entry = new BigDecimal(req.entryPrice());
        BigDecimal sl = new BigDecimal(req.slPrice());
        List<AccountLegPreview> legs = previewService.preview(req.symbol(), entry, sl);
        return new ProposalResponse(proposalId, legs);
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
