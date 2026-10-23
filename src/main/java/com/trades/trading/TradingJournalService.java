package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trades.persistence.TradeOrderMapper;
import com.trades.persistence.TradingJournalMapper;
import com.trades.persistence.TradingJournalMapper.ImportedLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Imports every order on the Tradier account into the trading journal tables. */
@Service
public class TradingJournalService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TradingJournalService.class);
    private static final String ENVIRONMENT = "SANDBOX";

    private final TradingJournalMapper mapper;
    private final TradeOrderMapper orderMapper;
    private final TradierGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    public TradingJournalService(TradingJournalMapper mapper, TradeOrderMapper orderMapper,
                                 TradierGateway gateway) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.gateway = gateway;
    }

    @Scheduled(initialDelayString = "${trades.journal-import.initial-delay-ms:20000}",
            fixedDelayString = "${trades.journal-import.interval-ms:300000}")
    public void scheduledImport() {
        try {
            importOrders();
        } catch (RuntimeException exception) {
            LOGGER.warn("Trading journal import failed: {}", exception.getMessage());
        }
    }

    /** Returns the number of order legs written. */
    @Transactional
    public int importOrders() {
        JsonNode orders = gateway.accountOrders().path("orders").path("order");
        int written = 0;
        for (JsonNode order : asList(orders)) {
            written += importOrder(order);
        }
        return written;
    }

    private int importOrder(JsonNode order) {
        String orderId = order.path("id").asText();
        String symbol = order.path("symbol").asText("").toUpperCase(Locale.ROOT);
        if (orderId.isBlank() || symbol.isBlank()) {
            return 0;
        }
        mapper.trackSymbol(symbol);
        int symbolPk = mapper.findSymbolPk(symbol);

        List<JsonNode> legNodes = asList(order.path("leg"));
        boolean multileg = !legNodes.isEmpty();
        if (!multileg) {
            legNodes = List.of(order);
        }

        List<SpreadClassifier.Leg> parsed = new ArrayList<>();
        for (JsonNode leg : legNodes) {
            parsed.add(SpreadClassifier.Leg.parse(leg.path("option_symbol").asText(null), decimal(leg, "quantity")));
        }
        String spreadType = SpreadClassifier.classify(parsed);

        int index = 0;
        for (JsonNode leg : legNodes) {
            SpreadClassifier.Leg option = parsed.get(index);
            String side = text(leg, "side");
            mapper.upsertLeg(new ImportedLeg(
                    orderId, multileg ? text(leg, "id") : orderId, index, symbolPk, spreadType,
                    option == null ? null : option.optionType(), action(side), ENVIRONMENT,
                    text(order, "class"), text(order, "type"), text(order, "status"), text(order, "duration"),
                    decimal(order, "quantity"), decimal(order, "price"), decimal(order, "avg_fill_price"),
                    decimal(order, "exec_quantity"), timestamp(order, "create_date"),
                    timestamp(order, "transaction_date"), side, text(leg, "option_symbol"),
                    option == null ? null : option.expiration(), option == null ? null : option.strike(),
                    text(leg, "status"), decimal(leg, "quantity"), decimal(leg, "avg_fill_price"),
                    decimal(leg, "exec_quantity"), decimal(leg, "remaining_quantity"), leg.toString()));
            index++;
        }
        syncOrder(order, orderId, symbol, symbolPk, spreadType, legNodes, parsed);
        return legNodes.size();
    }

    /** Mirrors a journal order into tradeOrders, tradeOrderLegs and tradeOrderEvents. */
    private void syncOrder(JsonNode order, String orderId, String symbol, int symbolPk, String spreadType,
                           List<JsonNode> legNodes, List<SpreadClassifier.Leg> parsed) {
        boolean optionOrder = !parsed.isEmpty() && parsed.stream().allMatch(leg -> leg != null)
                && legNodes.stream().allMatch(leg -> action(text(leg, "side")) != null);
        String type = text(order, "type");
        if (!optionOrder || type == null
                || !List.of("debit", "credit", "even").contains(type.toLowerCase(Locale.ROOT))) {
            return;
        }
        String tradierStatus = text(order, "status");
        String status = OrderStatusPoller.mapStatus(tradierStatus == null ? "" : tradierStatus);
        LocalDateTime created = timestamp(order, "create_date");
        LocalDateTime transacted = timestamp(order, "transaction_date");

        TradeOrderMapper.ImportedOrder imported = new TradeOrderMapper.ImportedOrder();
        imported.symbolPk = symbolPk;
        imported.tradierOrderId = orderId;
        imported.environment = ENVIRONMENT;
        imported.spreadType = spreadType;
        imported.orderType = type.toUpperCase(Locale.ROOT);
        imported.status = status;
        imported.tradierStatus = tradierStatus;
        BigDecimal price = decimal(order, "price");
        imported.limitPrice = price == null ? BigDecimal.ZERO : price;
        imported.submittedAt = created;
        imported.filledAt = "FILLED".equals(status) ? transacted : null;
        imported.cancelledAt = "CANCELLED".equals(status) ? transacted : null;

        Integer orderPk = orderMapper.findOrderPkByTradierId(orderId);
        String previousStatus = null;
        if (orderPk == null) {
            orderMapper.insertImportedOrder(imported);
            orderPk = imported.pk;
            int legIndex = 0;
            for (JsonNode leg : legNodes) {
                SpreadClassifier.Leg option = parsed.get(legIndex);
                orderMapper.insertLeg(orderPk, legIndex, option.optionType(), option.expiration(), option.strike(),
                        action(text(leg, "side")), decimal(leg, "quantity").intValue());
                legIndex++;
            }
            addEvent(symbolPk, orderPk, orderId, "SUBMIT", "POST /v1/accounts/{accountId}/orders",
                    submitRequest(order, legNodes, symbol), "{\"order\":{\"id\":" + orderId + ",\"status\":\"ok\"}}",
                    created);
        } else {
            previousStatus = orderMapper.findTradierStatus(orderPk);
            orderMapper.updateImportedOrder(orderPk, imported);
        }
        if (tradierStatus != null && !tradierStatus.equals(previousStatus)) {
            addEvent(symbolPk, orderPk, orderId, "STATUS", "GET /v1/accounts/{accountId}/orders",
                    null, "{\"order\":" + order + "}", transacted == null ? created : transacted);
        }
    }

    private void addEvent(int symbolPk, int orderPk, String orderId, String eventType, String endpoint,
                          String requestJson, String responseJson, LocalDateTime sentAt) {
        TradeOrderMapper.ImportedEvent event = new TradeOrderMapper.ImportedEvent();
        event.symbolPk = symbolPk;
        event.orderPk = orderPk;
        event.eventType = eventType;
        event.endpoint = endpoint;
        event.tradierOrderId = orderId;
        event.requestJson = requestJson;
        event.responseJson = responseJson;
        event.sentAt = sentAt;
        orderMapper.insertEventAt(event);
    }

    /** Recreates the form parameters the app would have sent for this order. */
    private String submitRequest(JsonNode order, List<JsonNode> legNodes, String symbol) {
        ObjectNode request = json.createObjectNode();
        request.putArray("class").add("multileg");
        request.putArray("symbol").add(symbol);
        request.putArray("type").add(text(order, "type"));
        request.putArray("duration").add(text(order, "duration") == null ? "day" : text(order, "duration"));
        if (decimal(order, "price") != null) {
            request.putArray("price").add(decimal(order, "price").stripTrailingZeros().toPlainString());
        }
        request.putArray("preview").add("false");
        int index = 0;
        for (JsonNode leg : legNodes) {
            request.putArray("option_symbol[" + index + "]").add(text(leg, "option_symbol"));
            request.putArray("side[" + index + "]").add(text(leg, "side"));
            request.putArray("quantity[" + index + "]").add(decimal(leg, "quantity").toBigInteger().toString());
            index++;
        }
        return request.toString();
    }

    private static String action(String side) {
        if (side == null) {
            return null;
        }
        String upper = side.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "BUY_TO_OPEN", "SELL_TO_OPEN", "BUY_TO_CLOSE", "SELL_TO_CLOSE" -> upper;
            default -> null;
        };
    }

    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull() || node.isTextual()) {
            return items;
        }
        if (node.isArray()) {
            node.forEach(items::add);
        } else if (node.isObject()) {
            items.add(node);
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : new BigDecimal(value.asText());
    }

    private static LocalDateTime timestamp(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault());
    }
}
