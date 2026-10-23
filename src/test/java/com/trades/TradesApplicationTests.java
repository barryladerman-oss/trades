package com.trades;

import com.trades.persistence.IvHistoryMapper;
import com.trades.trading.OptionLeg;
import com.trades.trading.OrderStatusPoller;
import com.trades.trading.OrderType;
import com.trades.trading.SpreadType;
import com.trades.trading.TradeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trades-test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
class TradesApplicationTests {
    @Autowired
    private IvHistoryMapper historyMapper;

    @Autowired
    private com.trades.persistence.TradeOrderMapper orderMapper;

    @Autowired
    private com.trades.trading.TradeOrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void recordsSubmittedOrderAndRetrievesItBySymbolWithLegs() throws Exception {
        TradeRequest trade = new TradeRequest("ordtst", SpreadType.VERTICAL,
                java.util.List.of(
                        new com.trades.trading.OptionLeg(com.trades.trading.OptionType.CALL,
                                LocalDate.of(2026, 12, 18), new BigDecimal("305"),
                                com.trades.trading.OpeningAction.BUY_TO_OPEN, 1),
                        new com.trades.trading.OptionLeg(com.trades.trading.OptionType.CALL,
                                LocalDate.of(2026, 12, 18), new BigDecimal("310"),
                                com.trades.trading.OpeningAction.SELL_TO_OPEN, 1)),
                com.trades.trading.OrderType.DEBIT, new BigDecimal("1.00"));

        orderService.record(trade, "SANDBOX",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("{\"order\":{\"id\":12345,\"partner_id\":\"abc\",\"status\":\"ok\"}}"));
        int orderPk = orderService.forSymbol("ORDTST").get(0).pk();
        orderService.recordEvent("ordtst", orderPk, "prev-1", "SUBMIT", true, "POST /v1/accounts/{accountId}/orders",
                java.util.Map.of("preview", java.util.List.of("false")),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("{\"order\":{\"id\":12345,\"partner_id\":\"abc\",\"status\":\"ok\"}}"), null);
        orderService.linkPreviewEvents("prev-1", orderPk);

        var orders = orderService.forSymbol("ORDTST");
        assertThat(orders).hasSize(1);
        var order = orders.get(0);
        assertThat(order.events()).extracting("eventType").containsExactly("SUBMIT");
        assertThat(order.events().get(0).tradierOrderId()).isEqualTo("12345");
        assertThat(order.events().get(0).tradierPartnerId()).isEqualTo("abc");
        assertThat(order.events().get(0).requestJson()).contains("\"preview\":[\"false\"]");
        assertThat(order.events().get(0).responseJson()).contains("\"partner_id\":\"abc\"");
        assertThat(order.symbol()).isEqualTo("ORDTST");
        assertThat(order.brokerOrderId()).isEqualTo("12345");
        assertThat(order.status()).isEqualTo(com.trades.trading.OrderStatus.OPEN);
        assertThat(order.limitPrice()).isEqualByComparingTo("1.00");
        assertThat(order.legs()).hasSize(2);
        assertThat(order.legs().get(1).action()).isEqualTo(com.trades.trading.OpeningAction.SELL_TO_OPEN);
        assertThat(order.legs().get(1).strike()).isEqualByComparingTo("310");
    }

    @Test
    void mapsTradierStatusesToOrderStatuses() {
        assertThat(OrderStatusPoller.mapStatus("open")).isEqualTo("OPEN");
        assertThat(OrderStatusPoller.mapStatus("pending")).isEqualTo("OPEN");
        assertThat(OrderStatusPoller.mapStatus("partially_filled")).isEqualTo("OPEN");
        assertThat(OrderStatusPoller.mapStatus("filled")).isEqualTo("FILLED");
        assertThat(OrderStatusPoller.mapStatus("canceled")).isEqualTo("CANCELLED");
        assertThat(OrderStatusPoller.mapStatus("expired")).isEqualTo("CANCELLED");
        assertThat(OrderStatusPoller.mapStatus("rejected")).isEqualTo("CANCELLED");
    }

    @Test
    void pollUpdatesOrderStatusAndRecordsChangeOnce() throws Exception {
        TradeRequest trade = new TradeRequest("polltst", SpreadType.SINGLE_LEG,
                java.util.List.of(new OptionLeg(com.trades.trading.OptionType.CALL, LocalDate.of(2026, 12, 18),
                        new BigDecimal("305"), com.trades.trading.OpeningAction.BUY_TO_OPEN, 1)),
                OrderType.DEBIT, new BigDecimal("1.00"));
        orderService.record(trade, "SANDBOX", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree("{\"order\":{\"id\":777,\"partner_id\":\"p\"}}"));
        var gateway = org.mockito.Mockito.mock(com.trades.trading.TradierGateway.class);
        org.mockito.Mockito.when(gateway.orderStatus("777")).thenReturn(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"order\":{\"id\":777,\"status\":\"filled\"}}"));
        var poller = new com.trades.trading.OrderStatusPoller(orderMapper, orderService, gateway);

        poller.pollOpenOrders();
        poller.pollOpenOrders();

        var order = orderService.forSymbol("POLLTST").get(0);
        assertThat(order.status()).isEqualTo(com.trades.trading.OrderStatus.FILLED);
        assertThat(order.events()).extracting("eventType").containsExactly("STATUS");
        assertThat(order.events().get(0).responseJson()).contains("filled");
    }

    @Test
    void mapperUsesIntegerKeysAndDeletesSamplesWhenSymbolIsRemoved() {
        String symbol = "TST";
        LocalDate observationDate = LocalDate.of(2026, 10, 22);
        historyMapper.trackSymbol(symbol);
        historyMapper.saveSample(symbol, observationDate, new BigDecimal("0.250000"));

        assertThat(historyMapper.findSample(symbol, observationDate))
                .isEqualByComparingTo("0.250000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ivDailySamples sample "
                        + "JOIN ivTrackedSymbols tracked "
                        + "ON tracked.pk = sample.ivTrackedSymbolsFk WHERE tracked.symbol = ?",
                Integer.class, symbol)).isEqualTo(1);

        historyMapper.deleteTrackedSymbol(symbol);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ivDailySamples sample "
                        + "JOIN ivTrackedSymbols tracked "
                        + "ON tracked.pk = sample.ivTrackedSymbolsFk WHERE tracked.symbol = ?",
                Integer.class, symbol)).isZero();
    }
}
