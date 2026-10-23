package com.trades.trading;

import java.math.BigDecimal;

public record UnderlyingQuote(BigDecimal bid, BigDecimal ask, BigDecimal last) {
}
