package com.trades.trading;

public class TradierRequestException extends RuntimeException {
    public TradierRequestException(String message) {
        super(message);
    }

    public TradierRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
