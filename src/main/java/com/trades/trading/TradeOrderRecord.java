package com.trades.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TradeOrderRecord(
        int pk,
        String symbol,
        String brokerOrderId,
        String tradierPartnerId,
        String environment,
        SpreadType spreadType,
        OrderType orderType,
        BigDecimal limitPrice,
        OrderStatus status,
        LocalDateTime submittedAt,
        LocalDateTime cancelledAt,
        List<Leg> legs,
        List<TradeOrderEventRecord> events
) {
    public record Leg(
            int legIndex,
            OptionType optionType,
            LocalDate expiration,
            BigDecimal strike,
            OpeningAction action,
            int quantity
    ) {
    }
}
