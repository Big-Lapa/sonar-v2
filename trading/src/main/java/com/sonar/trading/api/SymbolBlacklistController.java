package com.sonar.trading.api;

import com.sonar.trading.domain.entity.SymbolBlacklistEntity;
import com.sonar.trading.domain.repository.SymbolBlacklistRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 4.8: {@code GET /api/v1/strategy/blacklist} — symbols excluded before the universe reaches the LLM.
 *  Add/remove here too (`/ban`, stage 6) so the admin blacklist block (6) has a home to call. */
@RestController
@RequestMapping("/api/v1/strategy/blacklist")
public class SymbolBlacklistController {

    private final SymbolBlacklistRepository repository;

    public SymbolBlacklistController(SymbolBlacklistRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SymbolBlacklistEntity> list() {
        return repository.findAll();
    }

    public record AddRequest(String symbol, String reason) {}

    @PostMapping
    public SymbolBlacklistEntity add(@RequestBody AddRequest req) {
        return repository.save(new SymbolBlacklistEntity(req.symbol().toUpperCase(), req.reason()));
    }

    @DeleteMapping("/{symbol}")
    public void remove(@PathVariable String symbol) {
        repository.deleteById(symbol.toUpperCase());
    }
}
