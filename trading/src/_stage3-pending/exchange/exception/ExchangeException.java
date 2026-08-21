package com.sonar.trading.exchange.exception;

import com.sonar.trading.exchange.dto.ExchangeError;

public class ExchangeException extends RuntimeException {

    private final ExchangeError error;

    public ExchangeException(ExchangeError error) {
        super(error != null ? error.message() : null);
        this.error = error;
    }

    public ExchangeException(ExchangeError error, Throwable cause) {
        super(error != null ? error.message() : null, cause);
        this.error = error;
    }

    public ExchangeError getError() {
        return error;
    }
}
