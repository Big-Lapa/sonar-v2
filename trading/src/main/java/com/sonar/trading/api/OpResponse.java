package com.sonar.trading.api;

import com.sonar.trading.i18n.Msg;

import java.util.Map;

/**
 * Result of a manual operation (close, move stop, cancel order). {@code message} is the English
 * text; {@code code}/{@code params} let a client render it in its own language (null code = plain text).
 */
public record OpResponse(boolean ok, String message, String code, Map<String, String> params) {

    public static OpResponse of(boolean ok, Msg msg) {
        return new OpResponse(ok, msg.text(), msg.code().name(), msg.params());
    }

    /** English-only text with no translatable code (validation errors). */
    public static OpResponse plain(boolean ok, String message) {
        return new OpResponse(ok, message, null, Map.of());
    }
}
