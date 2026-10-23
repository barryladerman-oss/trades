package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class PositionController {
    private final TradierGateway gateway;
    private final OrderStatusPoller poller;
    private final TradeOrderService orders;
    private final JdbcTemplate jdbcTemplate;

    public PositionController(TradierGateway gateway, OrderStatusPoller poller, TradeOrderService orders,
                              JdbcTemplate jdbcTemplate) {
        this.gateway = gateway;
        this.poller = poller;
        this.orders = orders;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/orders/open")
    public List<TradeOrderRecord> openOrders() {
        return orders.between(LocalDate.of(2000, 1, 1), LocalDate.now()).stream()
                .filter(order -> order.status() == OrderStatus.OPEN)
                .toList();
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Map<String, String> cancel(@PathVariable String orderId) {
        poller.cancel(orderId);
        return Map.of("orderId", orderId, "status", "CANCELLED");
    }

    /**
     * Groups the account's option positions by the filled opening order that created them (newest first).
     * Quantities not explained by any journal order are grouped by underlying.
     */
    @GetMapping("/positions")
    public List<PositionGroup> positions() {
        Map<String, BigDecimal> remaining = new LinkedHashMap<>();
        Map<String, BigDecimal> costBasis = new LinkedHashMap<>();
        JsonNode node = gateway.accountPositions().path("positions").path("position");
        List<JsonNode> items = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(items::add);
        } else if (node.isObject()) {
            items.add(node);
        }
        for (JsonNode item : items) {
            String symbol = item.path("symbol").asText();
            BigDecimal quantity = item.path("quantity").decimalValue();
            if (SpreadClassifier.Leg.parse(symbol, quantity) != null && quantity.signum() != 0) {
                remaining.put(symbol, quantity);
                costBasis.put(symbol, item.path("cost_basis").decimalValue());
            }
        }

        List<PositionGroup> groups = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byOrder = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                SELECT tradierOrderId, legIndex, symbol, spreadType, side, optionSymbol, legQuantity,
                       legAvgFillPrice, CAST(orderCreatedAt AS CHAR) AS orderCreatedAt
                FROM tradingJournalLegDetails
                WHERE orderStatus = 'filled'
                ORDER BY orderCreatedAt DESC, tradierOrderId DESC, legIndex
                """).forEach(row -> byOrder.computeIfAbsent(String.valueOf(row.get("tradierOrderId")),
                key -> new ArrayList<>()).add(row));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byOrder.entrySet()) {
            List<Map<String, Object>> rows = entry.getValue();
            if (!rows.stream().allMatch(row -> String.valueOf(row.get("side")).endsWith("_to_open")
                    && row.get("optionSymbol") != null && row.get("legQuantity") != null)) {
                continue;
            }
            Map<String, BigDecimal> needed = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                BigDecimal quantity = new BigDecimal(row.get("legQuantity").toString());
                if (String.valueOf(row.get("side")).startsWith("sell")) {
                    quantity = quantity.negate();
                }
                needed.merge((String) row.get("optionSymbol"), quantity, BigDecimal::add);
            }
            boolean fits = needed.entrySet().stream().allMatch(need -> {
                BigDecimal have = remaining.get(need.getKey());
                return have != null && have.signum() == need.getValue().signum()
                        && have.abs().compareTo(need.getValue().abs()) >= 0;
            });
            if (!fits) {
                continue;
            }
            List<PositionLeg> legs = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String optionSymbol = (String) row.get("optionSymbol");
                BigDecimal signed = needed.get(optionSymbol);
                SpreadClassifier.Leg parsed = SpreadClassifier.Leg.parse(optionSymbol, signed);
                BigDecimal fill = row.get("legAvgFillPrice") == null ? BigDecimal.ZERO
                        : new BigDecimal(row.get("legAvgFillPrice").toString());
                legs.add(new PositionLeg(optionSymbol, parsed.optionType(), parsed.expiration(), parsed.strike(),
                        signed, fill.multiply(signed).multiply(BigDecimal.valueOf(100))));
            }
            needed.forEach((symbol, quantity) -> remaining.merge(symbol, quantity.negate(), BigDecimal::add));
            groups.add(new PositionGroup(entry.getKey(), (String) rows.get(0).get("orderCreatedAt"),
                    (String) rows.get(0).get("symbol"), (String) rows.get(0).get("spreadType"), legs));
        }

        Map<String, List<PositionLeg>> leftovers = new LinkedHashMap<>();
        remaining.forEach((symbol, quantity) -> {
            if (quantity.signum() == 0) {
                return;
            }
            SpreadClassifier.Leg parsed = SpreadClassifier.Leg.parse(symbol, quantity);
            leftovers.computeIfAbsent(symbol.substring(0, symbol.length() - 15), key -> new ArrayList<>())
                    .add(new PositionLeg(symbol, parsed.optionType(), parsed.expiration(), parsed.strike(),
                            quantity, costBasis.get(symbol)));
        });
        leftovers.forEach((underlying, legs) -> {
            List<PositionLeg> sorted = legs.stream()
                    .sorted(Comparator.comparing(PositionLeg::expiration)
                            .thenComparing(PositionLeg::optionType).thenComparing(PositionLeg::strike))
                    .toList();
            String spread = SpreadClassifier.classify(sorted.stream()
                    .map(leg -> new SpreadClassifier.Leg(leg.optionType(), leg.expiration(), leg.strike(),
                            leg.quantity())).toList());
            groups.add(new PositionGroup(null, null, underlying, spread, sorted));
        });
        return groups;
    }

    public record PositionLeg(String optionSymbol, String optionType, LocalDate expiration, BigDecimal strike,
                              BigDecimal quantity, BigDecimal costBasis) {
    }

    public record PositionGroup(String orderId, String createdAt, String symbol, String spreadType,
                                List<PositionLeg> legs) {
    }
}
