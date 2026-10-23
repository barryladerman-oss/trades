package com.trades.trading;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TradierSandboxClientTests {
    @Test
    void looksUpSymbolsForIncrementalQueries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/lookup")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("q", "SP"))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess("""
                        {"securities":{"security":[
                          {"symbol":"SPY","description":"SPDR S&P 500 ETF TRUST","type":"stock"},
                          {"symbol":"SPX","description":"S&P 500 INDEX","type":"index"}
                        ]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(List.of(
                new SymbolSuggestion("SPY", "SPDR S&P 500 ETF TRUST", "stock"),
                new SymbolSuggestion("SPX", "S&P 500 INDEX", "index")),
                client.symbols("sp"));
        server.verify();
    }

    @Test
    void ordersSymbolSuggestionsWithExactThenShorterSymbolsFirst() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/lookup")))
                .andRespond(withSuccess("""
                        {"securities":{"security":[
                          {"symbol":"VZ","description":"VERIZON COMMUNICATIONS","type":"stock"},
                          {"symbol":"VOO","description":"VANGUARD S&P 500 ETF","type":"etf"},
                          {"symbol":"V","description":"VISA INC","type":"stock"}
                        ]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(List.of("V", "VZ", "VOO"),
                client.symbols("v").stream().map(SymbolSuggestion::symbol).toList());
        server.verify();
    }

    @Test
    void fetchesUnderlyingLastPriceFromSandboxQuote() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/quotes")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("symbols", "SPY"))
                .andRespond(withSuccess("""
                        {"quotes":{"quote":{"symbol":"SPY","last":600.25,"bid":600.20,"ask":600.30}}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(new BigDecimal("600.25"), client.underlyingPrice("spy"));
        server.verify();
    }

    @Test
    void fetchesTodayOneMinuteUnderlyingTimesales() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ZoneId marketTimeZone = ZoneId.of("America/New_York");
        Clock marketClock = Clock.fixed(Instant.parse("2026-10-23T19:31:00Z"), marketTimeZone);
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"), marketClock);
        LocalDate marketDate = LocalDate.now(marketClock);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/timesales")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("symbol", "SPY"))
                .andExpect(queryParam("interval", "1min"))
                .andExpect(queryParam("start", marketDate + "%2009:30"))
                .andExpect(queryParam("end",
                        org.hamcrest.Matchers.startsWith(marketDate + "%20")))
                .andRespond(withSuccess("""
                        {"series":{"data":[
                          {"time":"%sT09:30:00","open":600.10,"high":600.40,"low":600.00,"close":600.30,"volume":1200},
                          {"time":"%sT09:31:00","open":600.30,"high":600.50,"low":600.20,"close":600.45,"volume":900}
                        ]}}
                        """.formatted(marketDate, marketDate),
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(List.of(
                new UnderlyingBar(marketDate + "T09:30:00", new BigDecimal("600.1"),
                        new BigDecimal("600.4"), new BigDecimal("600.0"),
                        new BigDecimal("600.3"), 1200),
                new UnderlyingBar(marketDate + "T09:31:00", new BigDecimal("600.3"),
                        new BigDecimal("600.5"), new BigDecimal("600.2"),
                        new BigDecimal("600.45"), 900)),
                client.underlyingTimesales("spy"));
        server.verify();
    }

    @Test
    void fetchesCurrentAndFutureSortedUniqueExpirationsWithMarketDataTokenOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"));
        LocalDate today = LocalDate.now();
        LocalDate expiration = today.plusDays(30);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/options/expirations")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("symbol", "SPY"))
                .andExpect(queryParam("includeAllRoots", "false"))
                .andExpect(queryParam("strikes", "false"))
                .andRespond(withSuccess("""
                        {"expirations":{"date":["%s","%s","2000-01-01","%s","%s"]}}
                        """.formatted(expiration.plusDays(7), expiration, expiration, today),
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(List.of(today, expiration, expiration.plusDays(7)), client.expirations("spy"));
        server.verify();
    }

    @Test
    void fetchesSortedUniqueStrikesForSelectedExpirationAndOptionType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("", "sandbox-token"));
        LocalDate expiration = LocalDate.now().plusDays(30);
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/options/chains")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("symbol", "SPY"))
                .andExpect(queryParam("expiration", expiration.toString()))
                .andExpect(queryParam("greeks", "false"))
                .andRespond(withSuccess("""
                        {"options":{"option":[
                          {"option_type":"call","strike":105.0},
                          {"option_type":"put","strike":90.0},
                          {"option_type":"call","strike":100.0},
                          {"option_type":"call","strike":105.0}
                        ]}}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        assertEquals(List.of(new BigDecimal("100.0"), new BigDecimal("105.0")),
                client.strikes("spy", expiration, OptionType.CALL));
        server.verify();
    }

    @Test
    void previewsMultilegOrderOnlyAtTradierSandbox() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("ACCT-123", "sandbox-token"));
        LocalDate expiration = LocalDate.now().plusDays(30);
        String firstOption = "SPY" + expiration.format(DateTimeFormatter.ofPattern("yyMMdd")) + "C00500000";
        String secondOption = "SPY" + expiration.format(DateTimeFormatter.ofPattern("yyMMdd")) + "C00505000";
        server.expect(requestTo("https://sandbox.tradier.com/v1/accounts/ACCT-123/orders"))
                .andExpect(method(Objects.requireNonNull(HttpMethod.POST)))
                .andExpect(header("Authorization", "Bearer sandbox-token"))
                .andExpect(content().string(Objects.requireNonNull(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("class=multileg"),
                        org.hamcrest.Matchers.containsString(firstOption),
                        org.hamcrest.Matchers.containsString(secondOption),
                        org.hamcrest.Matchers.containsString("side%5B%5D=buy_to_open"),
                        org.hamcrest.Matchers.containsString("quantity%5B%5D=1"),
                        org.hamcrest.Matchers.containsString("preview=true")))))
                .andRespond(withSuccess("{\"order\":{\"id\":\"preview-id\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        client.preview(new TradeRequest("SPY", SpreadType.VERTICAL,
                List.of(new OptionLeg(OptionType.CALL, expiration, new BigDecimal("500"), OpeningAction.BUY_TO_OPEN, 1),
                        new OptionLeg(OptionType.CALL, expiration, new BigDecimal("505"), OpeningAction.SELL_TO_OPEN, 1)),
                OrderType.DEBIT, new BigDecimal("1.00")));

        server.verify();
    }

    @Test
    void previewsSingleOptionUsingOptionOrderFormat() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("ACCT-123", "sandbox-token"));
        LocalDate expiration = LocalDate.now().plusDays(30);
        String optionSymbol = "SPY" + expiration.format(DateTimeFormatter.ofPattern("yyMMdd")) + "P00500000";
        server.expect(requestTo("https://sandbox.tradier.com/v1/accounts/ACCT-123/orders"))
                .andExpect(method(Objects.requireNonNull(HttpMethod.POST)))
                .andExpect(content().string(Objects.requireNonNull(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("class=option"),
                        org.hamcrest.Matchers.containsString("symbol=" + optionSymbol),
                        org.hamcrest.Matchers.containsString("side=buy_to_open"),
                        org.hamcrest.Matchers.containsString("quantity=1"),
                        org.hamcrest.Matchers.containsString("type=limit"),
                        org.hamcrest.Matchers.containsString("price=1.00"),
                        org.hamcrest.Matchers.containsString("preview=true")))))
                .andRespond(withSuccess("{\"order\":{\"id\":\"preview-id\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        client.preview(new TradeRequest("SPY", SpreadType.SINGLE_LEG,
                List.of(new OptionLeg(OptionType.PUT, expiration, new BigDecimal("500"),
                        OpeningAction.BUY_TO_OPEN, 1)),
                OrderType.DEBIT, new BigDecimal("1.00")));

        server.verify();
    }

    @Test
    void fetchesSandboxQuotesForAllOptionLegs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TradierSandboxClient client = new TradierSandboxClient(builder,
                new TradierProperties("ACCT-123", "sandbox-token"));
        LocalDate expiration = LocalDate.now().plusDays(30);
        String firstOption = "SPY" + expiration.format(DateTimeFormatter.ofPattern("yyMMdd")) + "C00500000";
        String secondOption = "SPY" + expiration.format(DateTimeFormatter.ofPattern("yyMMdd")) + "C00505000";
        server.expect(requestTo(Objects.requireNonNull(org.hamcrest.Matchers.containsString(
                        "https://sandbox.tradier.com/v1/markets/quotes"))))
                .andExpect(method(Objects.requireNonNull(HttpMethod.GET)))
                .andExpect(queryParam("symbols", Objects.requireNonNull(firstOption + "," + secondOption)))
                .andExpect(queryParam("greeks", Objects.requireNonNull("true")))
                .andRespond(withSuccess("""
                        {"quotes":{"quote":[
                          {"symbol":"%s","bid":1.0,"ask":1.2,"open_interest":1234,
                           "greeks":{"delta":0.62,"gamma":0.02,"theta":-0.04,
                                     "vega":0.10,"rho":0.03,"mid_iv":0.24}},
                          {"symbol":"%s","bid":0.4,"ask":0.6,"open_interest":42,
                           "greeks":{"delta":0.35,"gamma":0.015,"theta":-0.03,
                                     "vega":0.08,"rho":0.02,"mid_iv":0.26}}
                        ]}}
                        """.formatted(firstOption, secondOption),
                        org.springframework.http.MediaType.APPLICATION_JSON));

        List<OptionQuote> quotes = client.quotes(new TradeRequest("SPY", SpreadType.VERTICAL,
                List.of(new OptionLeg(OptionType.CALL, expiration, new BigDecimal("500"), OpeningAction.BUY_TO_OPEN, 1),
                        new OptionLeg(OptionType.CALL, expiration, new BigDecimal("505"), OpeningAction.SELL_TO_OPEN, 1)),
                OrderType.DEBIT, new BigDecimal("1.00")));

        org.junit.jupiter.api.Assertions.assertEquals(2, quotes.size());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("1.0"), quotes.getFirst().bid());
        org.junit.jupiter.api.Assertions.assertEquals(1234, quotes.getFirst().openInterest());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.62"), quotes.getFirst().delta());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.02"), quotes.getFirst().gamma());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("-0.04"), quotes.getFirst().theta());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.1"), quotes.getFirst().vega());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.03"), quotes.getFirst().rho());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.24"), quotes.getFirst().impliedVolatility());
        server.verify();
    }

    @Test
    void refusesToCallBrokerWithoutSandboxCredentials() {
        TradierSandboxClient client = new TradierSandboxClient(RestClient.builder(),
                new TradierProperties("", ""));
        TradeRequest trade = new TradeRequest("SPY", SpreadType.SINGLE_LEG,
                List.of(new OptionLeg(OptionType.CALL, LocalDate.now().plusDays(30), new BigDecimal("500"),
                        OpeningAction.BUY_TO_OPEN, 1)),
                OrderType.DEBIT, BigDecimal.ONE);
        org.springframework.web.server.ResponseStatusException exception = assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> client.preview(trade));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }
}
