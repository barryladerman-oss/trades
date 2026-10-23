package com.trades.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trades.persistence.TradeOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TradeOrderService {
    private final TradeOrderMapper mapper;
    private final ObjectMapper objectMapper;

    public TradeOrderService(TradeOrderMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int record(TradeRequest trade, String environment, JsonNode brokerResponse) {
        String symbol = trade.symbol().toUpperCase(Locale.ROOT);
        mapper.trackSymbol(symbol);
        TradeOrderMapper.NewOrder order = new TradeOrderMapper.NewOrder();
        order.symbolPk = mapper.findSymbolPk(symbol);
        order.tradierOrderId = text(brokerResponse, "id");
        order.tradierPartnerId = text(brokerResponse, "partner_id");
        order.environment = environment;
        order.spreadType = trade.spreadType().name();
        order.orderType = trade.orderType().name();
        order.limitPrice = trade.limitPrice();
        mapper.insertOrder(order);
        int index = 0;
        for (OptionLeg leg : trade.legs()) {
            mapper.insertLeg(order.pk, index++, leg.optionType().name(), leg.expiration(), leg.strike(),
                    leg.action().name(), leg.quantity());
        }
        return order.pk;
    }

    public void recordEvent(String symbol, Integer orderPk, String previewId, String eventType,
                            boolean succeeded, String endpoint, Map<String, List<String>> request,
                            JsonNode response, String errorMessage) {
        String normalized = symbol.toUpperCase(Locale.ROOT);
        mapper.trackSymbol(normalized);
        TradeOrderMapper.NewEvent event = new TradeOrderMapper.NewEvent();
        event.symbolPk = mapper.findSymbolPk(normalized);
        event.orderPk = orderPk;
        event.previewId = previewId;
        event.eventType = eventType;
        event.succeeded = succeeded;
        event.endpoint = endpoint;
        event.tradierOrderId = text(response, "id");
        event.tradierPartnerId = text(response, "partner_id");
        event.requestJson = json(request);
        event.responseJson = response != null ? json(response)
                : json(Map.of("error", errorMessage == null ? "unknown error" : errorMessage));
        mapper.insertEvent(event);
    }

    public void linkPreviewEvents(String previewId, int orderPk) {
        mapper.linkPreviewEvents(previewId, orderPk);
    }

    private static String text(JsonNode response, String field) {
        JsonNode node = response == null ? null : response.path("order").path(field);
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    public List<TradeOrderRecord> forSymbol(String symbol) {
        return withDetails(mapper.findOrdersBySymbol(symbol.toUpperCase(Locale.ROOT)));
    }

    public List<TradeOrderRecord> between(java.time.LocalDate from, java.time.LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The start date must not be after the end date.");
        }
        return withDetails(mapper.findOrdersBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    }

    private List<TradeOrderRecord> withDetails(List<TradeOrderRecord> found) {
        return found.stream()
                .map(order -> new TradeOrderRecord(order.pk(), order.symbol(), order.brokerOrderId(),
                        order.tradierPartnerId(),
                        order.environment(), order.spreadType(), order.orderType(), order.limitPrice(),
                        order.status(), order.submittedAt(), order.cancelledAt(),
                        mapper.findLegs(order.pk()), mapper.findEvents(order.pk())))
                .toList();
    }

    private String json(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
