package com.sonar.trading.api;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.PositionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/v1/trade/positions?account=&symbol=} — `/positions`, and how `/be`/`/move`
 *  resolve a symbol typed in Telegram to the position id their REST paths need. */
@RestController
@RequestMapping("/api/v1/trade/positions")
public class TradePositionsController {

    private final PositionRepository positionRepository;

    public TradePositionsController(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    @GetMapping
    public List<PositionEntity> list(@RequestParam(required = false) String account,
                                     @RequestParam(required = false) String symbol) {
        if (account != null && symbol != null) {
            return positionRepository.findByAccountIdAndSymbol(account, symbol).map(List::of).orElse(List.of());
        }
        if (account != null) {
            return positionRepository.findAllOpenByAccountId(account);
        }
        return positionRepository.findAllOpen();
    }
}
