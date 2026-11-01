package com.trades.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads order exports (CSV with ID, Parent ID, Symbol, Side, ...) from the statements folder and rebuilds them as
 * Tradier-style order objects. Rows sharing a Parent ID are the legs of one multileg order.
 */
@Component
public class OrdersCsv {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrdersCsv.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    public OrdersCsv(@Value("${trades.statements-dir:statements}") String directory) {
        this.directory = Path.of(directory);
    }

    public List<ObjectNode> load() {
        List<ObjectNode> orders = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return orders;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.naturalOrder()).toList()) {
                try {
                    orders.addAll(parse(Files.readString(file)));
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Could not read order export {}: {}", file.getFileName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not list order exports in {}: {}", directory, exception.getMessage());
        }
        return orders;
    }

    List<ObjectNode> parse(String csv) {
        List<List<String>> rows = rows(csv);
        if (rows.isEmpty() || !rows.get(0).contains("Parent ID")) {
            return List.of();
        }
        Map<String, Integer> column = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            column.put(rows.get(0).get(i), i);
        }
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            Map<String, String> record = new HashMap<>();
            column.forEach((name, index) -> record.put(name, index < row.size() ? row.get(index).trim() : ""));
            String parent = record.get("Parent ID");
            groups.computeIfAbsent(parent.isEmpty() ? record.get("ID") : parent, key -> new ArrayList<>()).add(record);
        }
        List<ObjectNode> orders = new ArrayList<>();
        groups.forEach((id, legs) -> orders.add(order(id, legs)));
        return orders;
    }

    /** Keys of the filled legs (trade date, option symbol, buy/sell, quantity), used to avoid double-importing. */
    public static Map<String, Integer> filledLegKeys(List<ObjectNode> orders) {
        Map<String, Integer> keys = new HashMap<>();
        for (ObjectNode order : orders) {
            for (var leg : order.path("leg")) {
                if ("filled".equals(leg.path("status").asText()) && leg.hasNonNull("exec_quantity")) {
                    keys.merge(fillKey(leg.path("transaction_date").asText(), leg.path("option_symbol").asText(),
                            leg.path("side").asText().startsWith("buy"),
                            new BigDecimal(leg.path("exec_quantity").asText())), 1, Integer::sum);
                }
            }
        }
        return keys;
    }

    /** Date is either an ISO instant (converted to the market's trade date) or a plain yyyy-MM-dd trade date. */
    public static String fillKey(String date, String optionSymbol, boolean buy, BigDecimal quantity) {
        String tradeDate = date.length() > 10
                ? Instant.parse(date).atZone(MARKET_ZONE).toLocalDate().toString() : date;
        return tradeDate + "|" + optionSymbol + "|" + (buy ? "B" : "S") + "|"
                + quantity.abs().stripTrailingZeros().toPlainString();
    }

    private ObjectNode order(String id, List<Map<String, String>> legs) {
        Map<String, String> first = legs.get(0);
        boolean equity = "equity".equalsIgnoreCase(first.get("Class"));
        ObjectNode order = json.createObjectNode();
        order.put("id", id);
        order.put("symbol", first.get("Symbol"));
        order.put("type", first.get("Type"));
        order.put("duration", first.get("Dur"));
        order.put("status", first.get("Status"));
        order.put("create_date", first.get("Create Date"));
        order.put("transaction_date", first.get("Transaction Date"));
        putDecimal(order, "quantity", first.get("QTY"));
        if (equity) {
            order.put("class", "equity");
            order.put("side", first.get("Side"));
            return order;
        }
        order.put("class", "multileg");
        putDecimal(order, "price", first.get("Price"));
        order.put("num_legs", legs.size());
        ArrayNode legArray = order.putArray("leg");
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal filled = null;
        for (Map<String, String> record : legs) {
            ObjectNode leg = legArray.addObject();
            leg.put("id", record.get("ID"));
            leg.put("type", record.get("Type"));
            leg.put("symbol", record.get("Symbol"));
            leg.put("side", record.get("Side"));
            leg.put("status", record.get("Status"));
            leg.put("transaction_date", record.get("Transaction Date"));
            leg.put("option_symbol", optionSymbol(record));
            putDecimal(leg, "quantity", record.get("QTY"));
            putDecimal(leg, "avg_fill_price", record.get("Last Fill Price"));
            putDecimal(leg, "exec_quantity", record.get("Last Fill Qty"));
            putDecimal(leg, "remaining_quantity", record.get("Remaining Qty"));
            BigDecimal fillPrice = decimal(record.get("Last Fill Price"));
            BigDecimal fillQuantity = decimal(record.get("Last Fill Qty"));
            if (fillPrice != null && fillQuantity != null && fillQuantity.signum() > 0) {
                boolean buy = record.get("Side").startsWith("buy");
                net = net.add(buy ? fillPrice : fillPrice.negate());
                filled = filled == null ? fillQuantity : filled.min(fillQuantity);
            }
        }
        if (filled != null) {
            order.put("avg_fill_price", net.abs());
            order.put("exec_quantity", filled);
        }
        return order;
    }

    private static String optionSymbol(Map<String, String> record) {
        String[] expiration = record.get("Expiration Date").split("-");
        BigDecimal strike = new BigDecimal(record.get("Strike")).movePointRight(3);
        return record.get("Symbol") + expiration[0].substring(2) + expiration[1] + expiration[2]
                + record.get("Option Type").substring(0, 1).toUpperCase(Locale.ROOT)
                + String.format("%08d", strike.intValue());
    }

    private void putDecimal(ObjectNode node, String field, String value) {
        BigDecimal number = decimal(value);
        if (number != null) {
            node.put(field, number);
        }
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static List<List<String>> rows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (row.stream().anyMatch(value -> !value.isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        row.add(cell.toString());
        if (row.stream().anyMatch(value -> !value.isEmpty())) {
            rows.add(row);
        }
        return rows;
    }
}
