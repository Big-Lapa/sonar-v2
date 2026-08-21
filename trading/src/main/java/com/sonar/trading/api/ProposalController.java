package com.sonar.trading.api;

import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
