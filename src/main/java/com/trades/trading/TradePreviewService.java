package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TradePreviewService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(TradePreviewService.class);
    private static final int MAX_PENDING_PREVIEWS = 1000;
    private static final String ORDERS_ENDPOINT = "POST /v1/accounts/{accountId}/orders";
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, PendingTrade> pending = new ConcurrentHashMap<>();
    private final TradeValidator validator;
    private final TradierGateway gateway;
    private final TradeOrderService orders;

    public TradePreviewService(TradeValidator validator, TradierGateway gateway, TradeOrderService orders) {
        this.validator = validator;
        this.gateway = gateway;
        this.orders = orders;
    }

    public PreviewResult preview(TradeRequest trade) {
        validator.validate(trade);
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> !entry.getValue().createdAt().plus(PREVIEW_LIFETIME).isAfter(now));
        if (pending.size() >= MAX_PENDING_PREVIEWS) {
            throw new IllegalStateException("Too many pending previews. Wait and try again.");
        }
        java.util.Map<String, java.util.List<String>> previewRequest = gateway.previewRequest(trade);
        String previewId = UUID.randomUUID().toString();
        JsonNode brokerPreview;
        try {
            brokerPreview = gateway.preview(trade);
        } catch (RuntimeException exception) {
            throw exception;
        }
        pending.put(previewId, new PendingTrade(trade, now));
        return new PreviewResult(previewId, "SANDBOX", previewRequest, brokerPreview);
    }

    public JsonNode submit(String previewId, String environment, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("Explicit order confirmation is required.");
        }
        if (!"SANDBOX".equals(environment)) {
            throw new IllegalArgumentException("Only the Tradier sandbox environment is supported.");
        }
        PendingTrade trade = pending.remove(previewId);
        if (trade == null || !trade.createdAt().plus(PREVIEW_LIFETIME).isAfter(Instant.now())) {
            throw new IllegalArgumentException("The preview is missing or expired. Preview the order again.");
        }
        java.util.Map<String, java.util.List<String>> request =
                new java.util.LinkedHashMap<>(gateway.previewRequest(trade.trade()));
        request.put("preview", java.util.List.of("false"));
        JsonNode response;
        try {
            response = gateway.submit(trade.trade());
        } catch (RuntimeException exception) {
            recordEvent(trade.trade(), null, previewId, "SUBMIT", false, request, null, exception.getMessage());
            throw exception;
        }
        try {
            int orderPk = orders.record(trade.trade(), environment, response);
            orders.recordEvent(trade.trade().symbol(), orderPk, previewId, "SUBMIT", true, ORDERS_ENDPOINT,
                    request, response, null);
        } catch (RuntimeException exception) {
            // The broker already accepted the order, so a recording failure must not hide that.
            LOGGER.error("Order was submitted to the broker but could not be recorded.", exception);
        }
        return response;
    }

    private void recordEvent(TradeRequest trade, Integer orderPk, String previewId, String eventType,
                             boolean succeeded, java.util.Map<String, java.util.List<String>> request,
                             JsonNode response, String error) {
        try {
            orders.recordEvent(trade.symbol(), orderPk, previewId, eventType, succeeded, ORDERS_ENDPOINT,
                    request, response, error);
        } catch (RuntimeException exception) {
            LOGGER.error("Could not record the {} event.", eventType, exception);
        }
    }

    public record PreviewResult(String previewId, String environment,
                                java.util.Map<String, java.util.List<String>> brokerRequest, JsonNode brokerPreview) {
    }

    private record PendingTrade(TradeRequest trade, Instant createdAt) {
    }
}
