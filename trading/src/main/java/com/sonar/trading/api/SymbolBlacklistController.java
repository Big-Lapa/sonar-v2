package com.sonar.trading.api;

import com.sonar.trading.domain.entity.SymbolBlacklistEntity;
import com.sonar.trading.domain.repository.SymbolBlacklistRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<?> add(@RequestBody AddRequest req) {
        String symbol = Symbols.normalize(req.symbol());
        if (symbol == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol: некорректный тикер"));
        }
        return ResponseEntity.ok(repository.save(new SymbolBlacklistEntity(symbol, req.reason())));
    }

    /** Deliberately not run through {@link Symbols}: a row stored before that validation existed
     *  must still be removable, whatever it contains. */
    @DeleteMapping("/{symbol}")
    public void remove(@PathVariable String symbol) {
        repository.deleteById(symbol.toUpperCase());
    }
}
