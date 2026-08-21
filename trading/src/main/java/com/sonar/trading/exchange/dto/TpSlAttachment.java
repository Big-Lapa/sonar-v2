package com.sonar.trading.exchange.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Optional TP/SL prices attached to an ENTRY order (T23). Null means not attached.
 *
 * <p>{@code tp1Price}/{@code tp1Quantity} — необязательный частичный фикс (volspike-pressure-v1,
 * параметр {@code partialTpEnabled}, по умолчанию выкл): отдельный reduce-only тейк на часть
 * позиции ближе основного TP. Null = частичного фикса нет. Перенос стопа в безубыток после
 * фикса ({@code beAfterPartial}) пока НЕ автоматизирован — включать partial TP осознанно.
 *
 * <p>{@code referencePrice} — цена, от которой уровни посчитаны в {@code ExecutionEngine}: это
 * закрытие последней свечи из {@code market.data}, т.е. цена СПОТА для символов с
 * {@code volume_source=SPOT}, и она уже до минуты устаревшая. Ордер при этом уходит на перп.
 * {@code tickSize} — шаг цены символа оттуда же. Пара нужна для {@link #rescaledTo(BigDecimal)}:
 * venue, которая ставит условные ноги ОТДЕЛЬНЫМИ ордерами уже после филла (Binance), пересчитывает
 * уровни от фактической цены входа, сохраняя ровно те проценты, что заявила стратегия. BingX
 * прикрепляет TP/SL к самому входному ордеру (attached OCO), т.е. знает уровни только ДО филла и
 * пересчёт применить не может — там уровни остаются на referencePrice.
 */
public record TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice,
                             BigDecimal tp1Price, BigDecimal tp1Quantity,
                             BigDecimal referencePrice, BigDecimal tickSize) {

    /** Обычный случай: только основная пара TP/SL. */
    public TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice) {
        this(takeProfitPrice, stopLossPrice, null, null, null, null);
    }

    public TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice,
                          BigDecimal tp1Price, BigDecimal tp1Quantity) {
        this(takeProfitPrice, stopLossPrice, tp1Price, tp1Quantity, null, null);
    }

    public boolean hasPartial() {
        return tp1Price != null && tp1Quantity != null && tp1Quantity.signum() > 0;
    }

    /**
     * Уровни, пересчитанные от ФАКТИЧЕСКОЙ цены входа. Все цены множатся на
     * {@code actualEntryPrice / referencePrice}, поэтому заявленные проценты сохраняются точно
     * (уровни строятся мультипликативно), включая частичную ногу. Объём {@code tp1Quantity} не
     * трогается — он от цены не зависит.
     *
     * <p>Возвращает {@code this}, если пересчитать не от чего: нет reference, нет фактической
     * цены (ордер ещё без филла), либо она равна reference. Это безопасный путь — уровни просто
     * остаются прежними, вход уже состоялся и позиция в любом случае должна получить защиту.
     */
    public TpSlAttachment rescaledTo(BigDecimal actualEntryPrice) {
        if (referencePrice == null || referencePrice.signum() <= 0
                || actualEntryPrice == null || actualEntryPrice.signum() <= 0
                || actualEntryPrice.compareTo(referencePrice) == 0) {
            return this;
        }
        BigDecimal ratio = actualEntryPrice.divide(referencePrice, 12, RoundingMode.HALF_UP);
        return new TpSlAttachment(
                scaled(takeProfitPrice, ratio),
                scaled(stopLossPrice, ratio),
                scaled(tp1Price, ratio),
                tp1Quantity,
                actualEntryPrice,
                tickSize);
    }

    private BigDecimal scaled(BigDecimal price, BigDecimal ratio) {
        return price == null ? null : roundToTick(price.multiply(ratio));
    }

    /** Округление к шагу цены символа; без известного шага цена остаётся как есть. */
    private BigDecimal roundToTick(BigDecimal price) {
        if (tickSize == null || tickSize.signum() <= 0) {
            return price;
        }
        if (tickSize.compareTo(BigDecimal.ONE) == 0) {
            return price.setScale(0, RoundingMode.HALF_UP);
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
    }
}
