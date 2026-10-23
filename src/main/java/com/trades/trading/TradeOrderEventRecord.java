package com.trades.trading;

import java.time.LocalDateTime;

public record TradeOrderEventRecord(
        int pk,
        Integer orderPk,
        String previewId,
        String eventType,
        boolean succeeded,
        String endpoint,
        String tradierOrderId,
        String tradierPartnerId,
        String requestJson,
        String responseJson,
        LocalDateTime sentAt
) {
}
