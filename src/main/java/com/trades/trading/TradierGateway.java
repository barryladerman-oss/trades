package com.trades.trading;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface TradierGateway {
    List<SymbolSuggestion> symbols(String query);

    List<LocalDate> expirations(String symbol);

    List<BigDecimal> strikes(String symbol, LocalDate expiration, OptionType optionType);

    BigDecimal underlyingPrice(String symbol);

    UnderlyingQuote underlyingQuote(String symbol);

    BigDecimal underlyingThirtyDayAtmIv(String symbol);

    List<UnderlyingBar> underlyingTimesales(String symbol);

    List<OptionQuote> quotes(TradeRequest trade);

    JsonNode preview(TradeRequest trade);

    default JsonNode accountOrders() {
        throw new UnsupportedOperationException("Account order listing is not supported.");
    }

    default JsonNode orderStatus(String orderId) {
        throw new UnsupportedOperationException("Order status lookup is not supported.");
    }

    default JsonNode accountPositions() {
        throw new UnsupportedOperationException("Account positions are not supported.");
    }

    default JsonNode cancelOrder(String orderId) {
        throw new UnsupportedOperationException("Order cancellation is not supported.");
    }

    default java.util.Map<String, List<String>> previewRequest(TradeRequest trade) {
        return java.util.Map.of();
    }

    JsonNode submit(TradeRequest trade);
}
