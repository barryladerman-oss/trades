package com.trades.trading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades/journal")
public class TradingJournalController {
    private final JdbcTemplate jdbcTemplate;

    public TradingJournalController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Map<String, Object>> journal() {
        return jdbcTemplate.queryForList("""
                SELECT tradierOrderId, legIndex, symbol, spreadType, orderStatus, side, optionType,
                       optionSymbol, CAST(expiration AS CHAR) AS expiration, strike, legQuantity,
                       legAvgFillPrice, legExecQuantity, CAST(orderCreatedAt AS CHAR) AS orderCreatedAt
                FROM tradingJournalLegDetails
                ORDER BY orderCreatedAt DESC, tradierOrderId DESC, legIndex
                """);
    }
}
