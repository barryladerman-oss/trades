package com.trades.trading;

public enum OpeningAction {
    BUY_TO_OPEN("buy_to_open", "BTO", true, false),
    SELL_TO_OPEN("sell_to_open", "STO", false, false),
    BUY_TO_CLOSE("buy_to_close", "BTC", true, true),
    SELL_TO_CLOSE("sell_to_close", "STC", false, true);

    private final String tradierValue;
    private final String code;
    private final boolean buy;
    private final boolean closing;

    OpeningAction(String tradierValue, String code, boolean buy, boolean closing) {
        this.tradierValue = tradierValue;
        this.code = code;
        this.buy = buy;
        this.closing = closing;
    }

    /** Abbreviation stored in the database (BTO, STO, BTC, STC). */
    public String code() {
        return code;
    }

    /** Returns the abbreviation for a Tradier side such as buy_to_open, or null if it is not one of the four. */
    public static String codeForTradierValue(String side) {
        if (side == null) {
            return null;
        }
        for (OpeningAction action : values()) {
            if (action.tradierValue.equalsIgnoreCase(side)) {
                return action.code;
            }
        }
        return null;
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
