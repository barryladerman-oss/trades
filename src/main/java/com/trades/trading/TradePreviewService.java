package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TradePreviewService {
    private static final int MAX_PENDING_PREVIEWS = 1000;
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, PendingTrade> pending = new ConcurrentHashMap<>();
    private final TradeValidator validator;
    private final TradierGateway gateway;

    public TradePreviewService(TradeValidator validator, TradierGateway gateway) {
        this.validator = validator;
        this.gateway = gateway;
    }

    public PreviewResult preview(TradeRequest trade) {
        validator.validate(trade);
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> !entry.getValue().createdAt().plus(PREVIEW_LIFETIME).isAfter(now));
        if (pending.size() >= MAX_PENDING_PREVIEWS) {
            throw new IllegalStateException("Too many pending previews. Wait and try again.");
        }
        JsonNode brokerPreview = gateway.preview(trade);
        String previewId = UUID.randomUUID().toString();
        pending.put(previewId, new PendingTrade(trade, now));
        return new PreviewResult(previewId, "SANDBOX", brokerPreview);
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
        return gateway.submit(trade.trade());
    }

    public record PreviewResult(String previewId, String environment, JsonNode brokerPreview) {
    }

    private record PendingTrade(TradeRequest trade, Instant createdAt) {
    }
}
