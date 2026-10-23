package com.trades.trading;

import java.math.BigDecimal;

public record UnderlyingBar(
        String time,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
}
