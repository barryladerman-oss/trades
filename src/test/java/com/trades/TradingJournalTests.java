package com.trades;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trades.persistence.TradingJournalMapper;
import com.trades.trading.SpreadClassifier;
import com.trades.trading.SpreadClassifier.Leg;
import com.trades.trading.TradierGateway;
import com.trades.trading.TradingJournalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:trades-test;MODE=MariaDB;DB_CLOSE_DELAY=-1")
class TradingJournalTests {
    @Autowired
    private TradingJournalMapper mapper;

    @Autowired
    private com.trades.persistence.TradeOrderMapper orderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Leg leg(String symbol, int quantity) {
        return Leg.parse(symbol, BigDecimal.valueOf(quantity));
    }

    @Test
    void classifiesSpreadsFromLegProperties() {
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00761000", 1)))).isEqualTo("SINGLE_LEG");
        assertThat(SpreadClassifier.classify(List.of(leg("GOOG261218C00305000", 1), leg("GOOG261218C00310000", 1))))
                .isEqualTo("VERTICAL");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00761000", 1), leg("SPY261026C00761000", 1))))
                .isEqualTo("CALENDAR");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00765000", 1), leg("SPY261026C00761000", 1))))
                .isEqualTo("DIAGONAL");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00761000", 1), leg("SPY261127P00761000", 1))))
                .isEqualTo("STRADDLE");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00761000", 1), leg("SPY261127P00750000", 1))))
                .isEqualTo("STRANGLE");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127C00750000", 1), leg("SPY261127C00760000", 2),
                leg("SPY261127C00770000", 1)))).isEqualTo("BUTTERFLY");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127P00740000", 1), leg("SPY261127P00750000", 1),
                leg("SPY261127C00770000", 1), leg("SPY261127C00780000", 1)))).isEqualTo("IRON_CONDOR");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127P00740000", 1), leg("SPY261127P00760000", 1),
                leg("SPY261127C00760000", 1), leg("SPY261127C00780000", 1)))).isEqualTo("IRON_BUTTERFLY");
        assertThat(SpreadClassifier.classify(List.of(leg("SPY261127P00740000", 1), leg("SPY261026P00750000", 1),
                leg("SPY261026C00770000", 1), leg("SPY261127C00780000", 1)))).isEqualTo("DOUBLE_DIAGONAL");
    }

    @Test
    void importsAccountOrdersIntoJournalAndGroupsThemByOrderId() throws Exception {
        TradierGateway gateway = mock(TradierGateway.class);
        when(gateway.accountOrders()).thenReturn(new ObjectMapper().readTree("""
                {"orders":{"order":[
                 {"id":9001,"type":"debit","symbol":"JRNL","side":"buy","quantity":2.0,"status":"filled",
                  "duration":"day","price":1.5,"avg_fill_price":1.4,"exec_quantity":2.0,
                  "create_date":"2026-10-23T18:20:46.397Z","transaction_date":"2026-10-23T18:20:46.428Z",
                  "class":"multileg","num_legs":2,"strategy":"spread","leg":[
                   {"id":9002,"type":"debit","symbol":"JRNL","side":"buy_to_open","quantity":1.0,
                    "status":"filled","option_symbol":"JRNL261127C00761000"},
                   {"id":9003,"type":"debit","symbol":"JRNL","side":"sell_to_open","quantity":1.0,
                    "status":"filled","option_symbol":"JRNL261026C00761000"}]},
                 {"id":9004,"type":"market","symbol":"JRNL","side":"buy","quantity":10.0,"status":"filled",
                  "duration":"day","class":"equity","create_date":"2026-10-23T18:20:46.397Z"}]}}
                """));
        TradingJournalService service = new TradingJournalService(mapper, orderMapper, gateway);

        assertThat(service.importOrders()).isEqualTo(3);
        assertThat(service.importOrders()).isEqualTo(3);

        List<Map<String, Object>> summary = jdbcTemplate.queryForList(
                "SELECT * FROM tradingJournalOrders WHERE symbol = 'JRNL' ORDER BY tradierOrderId");
        assertThat(summary).hasSize(2);
        assertThat(summary.get(0)).containsEntry("spreadType", "CALENDAR").containsEntry("legCount", 2L);
        assertThat(summary.get(1)).containsEntry("spreadType", "SINGLE_LEG");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tradingJournalLegDetails WHERE tradierOrderId = '9001' "
                        + "AND spreadType = 'CALENDAR'", Integer.class)).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tradeOrders WHERE tradierOrderId IN ('9001', '9004')", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tradeOrderLegs l JOIN tradeOrders o ON o.pk = l.tradeOrdersFk "
                        + "WHERE o.tradierOrderId = '9001'", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tradeOrderEvents WHERE tradierOrderId = '9001'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT os.code FROM tradeOrders o JOIN orderStatuses os ON os.pk = o.orderStatusesFk "
                        + "WHERE o.tradierOrderId = '9001'", String.class)).isEqualTo("FILLED");
    }
}
