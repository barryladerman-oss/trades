package com.trades.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MariaDB on Windows stores view files in lowercase, so CREATE OR REPLACE VIEW loses the camelCase
 * name. RENAME TABLE keeps the requested casing, so views are renamed after schema.sql runs.
 */
@Component
public class ViewNameNormalizer implements ApplicationRunner {
    static final List<String> VIEWS = List.of("ivHistory", "symbolSpreadSummary", "tradeOrderEventLog",
            "tradeOrderLegDetails", "tradeOrderSummary", "tradingJournalLegDetails", "tradingJournalOrders");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public ViewNameNormalizer(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        if (!isMariaDbOrMySql()) {
            return;
        }
        for (String view : VIEWS) {
            Integer current = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = DATABASE() "
                            + "AND BINARY table_name = ?", Integer.class, view);
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = DATABASE() "
                            + "AND LOWER(table_name) = LOWER(?)", Integer.class, view);
            if (exists != null && exists > 0 && (current == null || current == 0)) {
                String temp = "tmp" + view;
                jdbc.execute("RENAME TABLE `" + view.toLowerCase() + "` TO `" + temp + "`");
                jdbc.execute("RENAME TABLE `" + temp + "` TO `" + view + "`");
            }
        }
    }

    private boolean isMariaDbOrMySql() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            return product.contains("mariadb") || product.contains("mysql");
        }
    }
}
