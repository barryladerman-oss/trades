package com.trades.trading;

public enum OpeningAction {
    BUY_TO_OPEN("buy_to_open", true, false),
    SELL_TO_OPEN("sell_to_open", false, false),
    BUY_TO_CLOSE("buy_to_close", true, true),
    SELL_TO_CLOSE("sell_to_close", false, true);

    private final String tradierValue;
    private final boolean buy;
    private final boolean closing;

    OpeningAction(String tradierValue, boolean buy, boolean closing) {
        this.tradierValue = tradierValue;
        this.buy = buy;
        this.closing = closing;
    }

    public String tradierValue() {
        return tradierValue;
    }

    public boolean isBuy() {
        return buy;
    }

    public boolean isClosing() {
        return closing;
    }
}
