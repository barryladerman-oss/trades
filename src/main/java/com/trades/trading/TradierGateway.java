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

    JsonNode submit(TradeRequest trade);
}
