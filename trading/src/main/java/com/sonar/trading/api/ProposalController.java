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

    /**
     * @param notional manual-panel sizing: qty = notional / entryPrice. Null keeps the risk%-based
     *                 path (previous behaviour).
     * @param leverage exchange leverage to set before entry. Null leaves whatever's already set.
     * @param orderType MARKET or LIMIT. Null/blank defaults to MARKET (previous behaviour). For
     *                  LIMIT, {@code entryPrice} IS the resting limit price — the panel already
     *                  sends the operator's typed limit price there (see referenceEntry() in
     *                  index.html), so no separate field is needed to carry it through execution.
     */
    public record CreateProposalRequest(
            String accountId, String symbol, String entryPrice, String slPrice, String tpPrice, String rationale,
            String notional, Integer leverage, String orderType) {}

    public record ProposalResponse(String proposalId, List<AccountLegPreview> accounts) {}

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateProposalRequest req) {
        if (req.accountId() == null || req.accountId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accountId обязателен — укажи, на каком аккаунте открывать"));
        }

        String symbol = Symbols.normalize(req.symbol());
        if (symbol == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol: некорректный тикер"));
        }

        BigDecimal entry = parseOrNull(req.entryPrice());
        BigDecimal sl = parseOrNull(req.slPrice());
        BigDecimal manualNotional = req.notional() != null && !req.notional().isBlank()
                ? parseOrNull(req.notional()) : null;
        if (entry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "entryPrice: некорректное число"));
        }
        if (sl == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "slPrice: некорректное число"));
        }
        if (req.notional() != null && !req.notional().isBlank() && manualNotional == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "notional: некорректное число"));
        }
        if (req.tpPrice() != null && !req.tpPrice().isBlank() && parseOrNull(req.tpPrice()) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tpPrice: некорректное число"));
        }
        String orderType = req.orderType() != null && !req.orderType().isBlank()
                ? req.orderType().toUpperCase(java.util.Locale.ROOT) : "MARKET";
        if (!"MARKET".equals(orderType) && !"LIMIT".equals(orderType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderType: MARKET или LIMIT"));
        }

        String proposalId = UUID.randomUUID().toString();
        String limitPrice = "LIMIT".equals(orderType) ? req.entryPrice() : null;
        ProposalEntity entity = ProposalEntity.pending(proposalId, symbol, "LONG",
                req.entryPrice(), req.slPrice(), req.tpPrice(), req.rationale(),
                System.currentTimeMillis(), req.accountId(), req.notional(), req.leverage(),
                orderType, limitPrice);
        proposals.save(entity);

        List<AccountLegPreview> legs = previewService.preview(symbol, entry, sl, req.accountId(), manualNotional);
        return ResponseEntity.ok(new ProposalResponse(proposalId, legs));
    }

    private static BigDecimal parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
