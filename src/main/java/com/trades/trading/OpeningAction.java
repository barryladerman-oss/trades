package com.trades.trading;

public enum OpeningAction {
    BUY_TO_OPEN("buy_to_open"),
    SELL_TO_OPEN("sell_to_open");

    private final String tradierValue;

    OpeningAction(String tradierValue) {
        this.tradierValue = tradierValue;
    }

    public String tradierValue() {
        return tradierValue;
    }
}
