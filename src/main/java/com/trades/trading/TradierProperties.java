package com.trades.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tradier")
public record TradierProperties(String accountId, String token) {
}
