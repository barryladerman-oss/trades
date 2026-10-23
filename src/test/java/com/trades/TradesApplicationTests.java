package com.trades;

import com.trades.persistence.IvHistoryMapper;
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
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
