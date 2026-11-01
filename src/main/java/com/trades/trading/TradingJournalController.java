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
    private final TradierEnvironment environmentSource;

    public TradingJournalController(JdbcTemplate jdbcTemplate, TradierEnvironment environmentSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.environmentSource = environmentSource;
    }

    @GetMapping
    public List<Map<String, Object>> journal() {
        return jdbcTemplate.queryForList("""
                SELECT tradierOrderId, legIndex, symbol, spreadType, orderStatus, side, optionType,
                       optionSymbol, CAST(expiration AS CHAR) AS expiration, strike, legQuantity,
                       legAvgFillPrice, legExecQuantity, CAST(orderCreatedAt AS CHAR) AS orderCreatedAt,
                       environment, tradierPartner
                FROM tradingJournalLegDetails
                WHERE environment = ?
                ORDER BY orderCreatedAt DESC, tradierOrderId DESC, legIndex
                """, environmentSource.environment());
    }
}
