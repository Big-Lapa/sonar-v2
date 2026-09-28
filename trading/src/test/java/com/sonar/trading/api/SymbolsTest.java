package com.sonar.trading.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SymbolsTest {

    @Test
    void acceptsPlainTickers() {
        assertEquals("BTCUSDT", Symbols.normalize("BTCUSDT"));
        assertEquals("1000PEPEUSDT", Symbols.normalize(" 1000pepeusdt "));
        assertEquals("BTC-USDT", Symbols.normalize("btc-usdt"));
        assertEquals("BTC_USDT_PERP", Symbols.normalize("BTC_USDT_PERP"));
    }

    @Test
    void rejectsMarkupAndGarbage() {
        assertNull(Symbols.normalize(null));
        assertNull(Symbols.normalize(""));
        assertNull(Symbols.normalize("B"));
        assertNull(Symbols.normalize("<IMG SRC=X ONERROR=&#X61;LERT(1)>"));
        assertNull(Symbols.normalize("BTC\"USDT"));
        assertNull(Symbols.normalize("BTCUSDT&side=SELL"));
        assertNull(Symbols.normalize("-BTCUSDT"));
        assertNull(Symbols.normalize("A".repeat(41)));
    }
}
