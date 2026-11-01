package com.sonar.trading.config;

import java.math.BigDecimal;

/** Exchange contract constraints for one symbol (clamp inputs). */
public record SymbolRules(BigDecimal stepSize, BigDecimal minQty, BigDecimal minNotional, BigDecimal tickSize) {

    public static final SymbolRules DEFAULT =
            new SymbolRules(new BigDecimal("0.001"), new BigDecimal("0.001"), BigDecimal.ZERO, new BigDecimal("0.01"));
}
