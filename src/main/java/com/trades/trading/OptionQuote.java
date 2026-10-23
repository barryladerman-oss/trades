package com.trades.trading;

import java.math.BigDecimal;

public record OptionQuote(String optionSymbol, BigDecimal bid, BigDecimal ask, Integer openInterest,
                          BigDecimal delta, BigDecimal gamma, BigDecimal theta, BigDecimal vega,
                          BigDecimal rho, BigDecimal impliedVolatility) {
    public OptionQuote(String optionSymbol, BigDecimal bid, BigDecimal ask) {
        this(optionSymbol, bid, ask, null, null, null, null, null, null, null);
    }

    public OptionQuote(String optionSymbol, BigDecimal bid, BigDecimal ask, Integer openInterest) {
        this(optionSymbol, bid, ask, openInterest, null, null, null, null, null, null);
    }

    public OptionQuote(String optionSymbol, BigDecimal bid, BigDecimal ask, Integer openInterest,
                       BigDecimal delta, BigDecimal theta, BigDecimal impliedVolatility) {
        this(optionSymbol, bid, ask, openInterest, delta, null, theta, null, null, impliedVolatility);
    }
}
