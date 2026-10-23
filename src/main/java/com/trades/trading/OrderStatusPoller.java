package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.trades.persistence.TradeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OrderStatusPoller {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderStatusPoller.class);

    private final TradeOrderMapper mapper;
    private final TradeOrderService orders;
    private final TradierGateway gateway;

    public OrderStatusPoller(TradeOrderMapper mapper, TradeOrderService orders, TradierGateway gateway) {
        this.mapper = mapper;
        this.orders = orders;
        this.gateway = gateway;
    }

    @Scheduled(initialDelayString = "${trades.order-poll.initial-delay-ms:10000}",
            fixedDelayString = "${trades.order-poll.interval-ms:30000}")
    public void pollOpenOrders() {
        for (TradeOrderMapper.TrackedOrder order : mapper.findOpenOrders()) {
            try {
                poll(order);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not poll Tradier status for order {}: {}", order.tradierOrderId(),
                        exception.getMessage());
            }
        }
    }

    void poll(TradeOrderMapper.TrackedOrder order) {
        JsonNode response = gateway.orderStatus(order.tradierOrderId());
        String tradierStatus = response.path("order").path("status").asText(null);
        if (tradierStatus == null || tradierStatus.isBlank()) {
            return;
        }
        String status = mapStatus(tradierStatus);
        if (!tradierStatus.equals(order.tradierStatus())) {
            orders.recordEvent(order.symbol(), order.orderPk(), null, "STATUS", true,
                    "GET /v1/accounts/{accountId}/orders/" + order.tradierOrderId(), Map.of(), response, null);
        }
        mapper.updateStatus(order.orderPk(), status, tradierStatus);
    }

    public static String mapStatus(String tradierStatus) {
        return switch (tradierStatus.toLowerCase(Locale.ROOT)) {
            case "filled" -> "FILLED";
            case "canceled", "cancelled", "expired", "rejected", "error" -> "CANCELLED";
            default -> "OPEN";
        };
    }
}
