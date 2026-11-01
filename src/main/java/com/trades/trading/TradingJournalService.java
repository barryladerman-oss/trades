package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trades.persistence.TradeOrderMapper;
import com.trades.persistence.TradingJournalMapper;
import com.trades.persistence.TradingJournalMapper.ImportedLeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Imports every order on the Tradier account into the trading journal tables. */
@Service
public class TradingJournalService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TradingJournalService.class);
    private static final String OPTION_ALPHA_PARTNER_ID = "3a8bbee1-5184-4ffe-8a0c-294fbad1aee9";
    private static final java.util.regex.Pattern OCC_SYMBOL =
            java.util.regex.Pattern.compile("^([A-Z.]+)(\\d{6})[CP]\\d{8}$");
    private static final java.util.regex.Pattern LEG_ID = java.util.regex.Pattern.compile("^(.+)-\\d+$");

    private final TradingJournalMapper mapper;
    private final TradeOrderMapper orderMapper;
    private final TradierGateway gateway;
    private final TradierEnvironment environmentSource;
    private final ObjectMapper json = new ObjectMapper();

    public TradingJournalService(TradingJournalMapper mapper, TradeOrderMapper orderMapper,
                                 TradierGateway gateway) {
        this(mapper, orderMapper, gateway, new TradierEnvironment(new TradierProperties(null, null)));
    }

    @Autowired
    public TradingJournalService(TradingJournalMapper mapper, TradeOrderMapper orderMapper,
                                 TradierGateway gateway, TradierEnvironment environmentSource) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
        this.gateway = gateway;
        this.environmentSource = environmentSource;
    }

    private boolean historyImported;

    @Scheduled(initialDelayString = "${trades.journal-import.initial-delay-ms:20000}",
            fixedDelayString = "${trades.journal-import.interval-ms:300000}")
    public void scheduledImport() {
        try {
            importOrders();
            if (!historyImported) {
                historyImported = true;
                importHistory();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Trading journal import failed: {}", exception.getMessage());
        }
    }

    /** Returns the number of order legs written. */
    @Transactional
    public int importOrders() {
        String environment = environmentSource.environment();
        int written = importEnvironment(gateway.accountOrders(), environment);
        String other = TradierProperties.LIVE.equals(environment) ? TradierProperties.SANDBOX : TradierProperties.LIVE;
        if (environmentSource.available(other)) {
            try {
                written += importEnvironment(gateway.accountOrders(other), other);
            } catch (RuntimeException exception) {
                LOGGER.warn("Trading journal import for {} failed: {}", other, exception.getMessage());
            }
        }
        return written;
    }

    /**
     * Imports earlier trades from the account history feed, which has no order ids, so each option fill becomes
     * its own journal order. Open/close is inferred by replaying fills against a running position per contract.
     */
    @Transactional
    public int importHistory() {
        String environment = environmentSource.environment();
        int written = 0;
        for (String target : List.of(environment, TradierProperties.LIVE.equals(environment)
                ? TradierProperties.SANDBOX : TradierProperties.LIVE)) {
            if (!target.equals(environment) && !environmentSource.available(target)) {
                continue;
            }
            try {
                written += importHistoryEvents(gateway.accountHistory(target), target);
            } catch (RuntimeException exception) {
                LOGGER.warn("Trading journal history import for {} failed: {}", target, exception.getMessage());
            }
        }
        return written;
    }

    private int importHistoryEvents(JsonNode response, String environment) {
        List<JsonNode> events = asList(response.path("history").path("event"));
        java.util.Collections.reverse(events);
        events.sort(java.util.Comparator.comparing(event -> text(event, "date") == null ? "" : text(event, "date")));
        java.util.Map<String, BigDecimal> positions = new java.util.HashMap<>();
        java.util.Map<String, Integer> duplicates = new java.util.HashMap<>();
        List<JsonNode> fills = new ArrayList<>();
        for (JsonNode event : events) {
            String type = text(event, "type");
            if ("option".equals(type)) {
                positions.remove(text(event.path("option"), "symbol"));
                continue;
            }
            JsonNode trade = event.path("trade");
            String optionSymbol = text(trade, "symbol");
            if (!"trade".equals(type) || !"option".equals(text(trade, "trade_type")) || optionSymbol == null
                    || decimal(trade, "quantity") == null || text(event, "date") == null) {
                continue;
            }
            java.util.regex.Matcher matcher = OCC_SYMBOL.matcher(optionSymbol);
            if (!matcher.matches()) {
                continue;
            }
            BigDecimal signed = decimal(trade, "quantity");
            BigDecimal quantity = signed.abs();
            boolean buy = signed.signum() > 0;
            BigDecimal position = positions.getOrDefault(optionSymbol, BigDecimal.ZERO);
            boolean closing = buy ? position.signum() < 0 : position.signum() > 0;
            positions.put(optionSymbol, position.add(signed));

            String date = text(event, "date");
            String base = "H" + date.substring(0, 10).replace("-", "") + "-" + matcher.group(1) + matcher.group(2)
                    + (closing ? "C" : "O");
            int sequence = duplicates.merge(base, 1, Integer::sum);
            ObjectNode order = json.createObjectNode();
            order.put("id", base + "-" + sequence);
            order.put("class", "option");
            order.put("symbol", matcher.group(1));
            order.put("option_symbol", optionSymbol);
            order.put("side", (buy ? "buy" : "sell") + (closing ? "_to_close" : "_to_open"));
            order.put("type", buy ? "debit" : "credit");
            order.put("status", "filled");
            order.put("duration", "day");
            order.put("quantity", quantity);
            order.put("price", decimal(trade, "price"));
            order.put("avg_fill_price", decimal(trade, "price"));
            order.put("exec_quantity", quantity);
            order.put("create_date", date);
            order.put("transaction_date", date);
            fills.add(order);
        }
        int written = 0;
        for (JsonNode order : mergeLegs(fills)) {
            written += importOrder(order, environment);
        }
        return written;
    }

    private int importEnvironment(JsonNode response, String environment) {
        int written = 0;
        for (JsonNode order : mergeLegs(asList(response.path("orders").path("order")))) {
            written += importOrder(order, environment);
        }
        return written;
    }

    /**
     * Single-leg orders whose ids differ only by the digit after the final "-" are the legs of one multileg order;
     * they are combined under the shared id prefix so the spread type is inferred from all the legs.
     */
    private List<JsonNode> mergeLegs(List<JsonNode> orders) {
        java.util.Map<String, List<JsonNode>> groups = new java.util.LinkedHashMap<>();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode order : orders) {
            java.util.regex.Matcher matcher = LEG_ID.matcher(order.path("id").asText(""));
            if (matcher.matches() && !order.has("leg") && text(order, "option_symbol") != null) {
                groups.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(order);
            } else {
                result.add(order);
            }
        }
        groups.forEach((base, legs) -> result.add(legs.size() == 1 ? legs.get(0) : combine(base, legs)));
        return result;
    }

    private JsonNode combine(String base, List<JsonNode> legs) {
        JsonNode first = legs.get(0);
        ObjectNode combined = json.createObjectNode();
        combined.put("id", base);
        combined.put("class", "multileg");
        combined.put("symbol", text(first, "symbol"));
        combined.put("duration", text(first, "duration"));
        combined.put("status", text(first, "status"));
        combined.put("create_date", text(first, "create_date"));
        combined.put("transaction_date", text(first, "transaction_date"));
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal quantity = null;
        var legArray = combined.putArray("leg");
        for (JsonNode leg : legs) {
            BigDecimal legQuantity = decimal(leg, "quantity") == null ? BigDecimal.ONE : decimal(leg, "quantity");
            BigDecimal price = decimal(leg, "avg_fill_price") == null ? decimal(leg, "price") : decimal(leg, "avg_fill_price");
            boolean buy = isBuy(text(leg, "side"));
            if (price != null) {
                net = net.add(price.multiply(legQuantity).multiply(buy ? BigDecimal.ONE : BigDecimal.ONE.negate()));
            }
            quantity = quantity == null ? legQuantity : quantity.min(legQuantity);
            legArray.add(leg);
        }
        String type = net.signum() > 0 ? "debit" : net.signum() < 0 ? "credit" : "even";
        BigDecimal perSpread = net.abs().divide(quantity, 2, java.math.RoundingMode.HALF_UP);
        combined.put("type", type);
        combined.put("quantity", quantity);
        combined.put("price", perSpread);
        combined.put("avg_fill_price", perSpread);
        combined.put("exec_quantity", quantity);
        combined.put("num_legs", legs.size());
        return combined;
    }

    private static boolean isBuy(String side) {
        return side != null && side.toLowerCase(Locale.ROOT).startsWith("buy");
    }

    private int importOrder(JsonNode order, String environment) {
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
                    option == null ? null : option.optionType(), action(side), environment,
                    text(order, "class"), text(order, "type"), text(order, "status"), text(order, "duration"),
                    decimal(order, "quantity"), decimal(order, "price"), decimal(order, "avg_fill_price"),
                    decimal(order, "exec_quantity"), timestamp(order, "create_date"),
                    timestamp(order, "transaction_date"), action(side) == null ? side : action(side),
                    text(leg, "option_symbol"),
                    option == null ? null : option.expiration(), option == null ? null : option.strike(),
                    text(leg, "status"), decimal(leg, "quantity"), decimal(leg, "avg_fill_price"),
                    decimal(leg, "exec_quantity"), decimal(leg, "remaining_quantity"), leg.toString()));
            index++;
        }
        syncOrder(order, orderId, symbol, symbolPk, spreadType, legNodes, parsed, environment);
        return legNodes.size();
    }

    /** Mirrors a journal order into tradeOrders, tradeOrderLegs and tradeOrderEvents. */
    private void syncOrder(JsonNode order, String orderId, String symbol, int symbolPk, String spreadType,
                           List<JsonNode> legNodes, List<SpreadClassifier.Leg> parsed, String environment) {
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
        imported.environment = environment;
        imported.tradierPartnerId = importedPartner(order, environment, created);
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

    /** Live orders from before today came through Option Alpha; otherwise use Tradier's own partner id if given. */
    private String importedPartner(JsonNode order, String environment, LocalDateTime created) {
        String partner = text(order, "partner_id");
        if (partner != null) {
            return partner;
        }
        if (TradierProperties.LIVE.equals(environment) && created != null
                && created.toLocalDate().isBefore(LocalDate.now())) {
            return OPTION_ALPHA_PARTNER_ID;
        }
        return null;
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
        return OpeningAction.codeForTradierValue(side);
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
