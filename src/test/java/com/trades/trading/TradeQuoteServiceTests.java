package com.trades.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeQuoteServiceTests {
    private final TradierGateway gateway = mock(TradierGateway.class);
    private final TradeQuoteService service = new TradeQuoteService(new TradeValidator(), gateway);
    private final LocalDate expiration = LocalDate.now().plusDays(30);
    private final OptionLeg longLeg = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("100"),
            OpeningAction.BUY_TO_OPEN, 1);
    private final OptionLeg shortLeg = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("105"),
            OpeningAction.SELL_TO_OPEN, 1);
    private final String longSymbol = TradierSandboxClient.optionSymbol("SPY", longLeg);
    private final String shortSymbol = TradierSandboxClient.optionSymbol("SPY", shortLeg);

    @Test
    void searchesSymbolsStartingWithTheFirstTypedLetter() {
        List<SymbolSuggestion> suggestions = List.of(new SymbolSuggestion("SPY", "SPDR S&P 500 ETF TRUST", "stock"));
        when(gateway.symbols("S")).thenReturn(suggestions);

        assertEquals(suggestions, service.symbols("s"));
        assertThrows(IllegalArgumentException.class, () -> service.symbols(""));
    }

    @Test
    void retrievesAvailableExpirationsForSymbolWithoutOrderAccount() {
        List<LocalDate> available = List.of(expiration, expiration.plusDays(7));
        when(gateway.expirations("SPY")).thenReturn(available);

        assertEquals(available, service.expirations("SPY"));
    }

    @Test
    void retrievesUnderlyingTimesalesForValidSymbol() {
        List<UnderlyingBar> bars = List.of(new UnderlyingBar("2026-10-23T09:30:00",
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                new BigDecimal("100.5"), 1200));
        when(gateway.underlyingTimesales("SPY")).thenReturn(bars);

        assertEquals(bars, service.underlyingTimesales("SPY"));
        assertThrows(IllegalArgumentException.class, () -> service.underlyingTimesales("../"));
    }

    @Test
    void aggregatesMinuteBarsIntoRequestedCandleInterval() {
        when(gateway.underlyingTimesales("SPY")).thenReturn(List.of(
                new UnderlyingBar("2026-10-23T09:30:00", new BigDecimal("100"), new BigDecimal("101"),
                        new BigDecimal("99"), new BigDecimal("100.5"), 100),
                new UnderlyingBar("2026-10-23T09:44:00", new BigDecimal("100.5"), new BigDecimal("103"),
                        new BigDecimal("100"), new BigDecimal("102"), 200),
                new UnderlyingBar("2026-10-23T09:45:00", new BigDecimal("102"), new BigDecimal("104"),
                        new BigDecimal("101"), new BigDecimal("103"), 50)));

        List<UnderlyingBar> candles = service.underlyingTimesales("SPY", 15);

        assertEquals(List.of(
                new UnderlyingBar("2026-10-23T09:30:00", new BigDecimal("100"), new BigDecimal("103"),
                        new BigDecimal("99"), new BigDecimal("102"), 300),
                new UnderlyingBar("2026-10-23T09:45:00", new BigDecimal("102"), new BigDecimal("104"),
                        new BigDecimal("101"), new BigDecimal("103"), 50)), candles);
        assertThrows(IllegalArgumentException.class, () -> service.underlyingTimesales("SPY", 7));
    }

    @Test
    void retrievesUnderlyingPriceForAtTheMoneyStrikeSelection() {
        when(gateway.underlyingPrice("SPY")).thenReturn(new BigDecimal("600.25"));

        assertEquals(new BigDecimal("600.25"), service.underlyingPrice("SPY"));
    }

    @Test
    void retrievesAvailableStrikesForSelectedExpiration() {
        List<BigDecimal> available = List.of(new BigDecimal("95"), new BigDecimal("100"));
        when(gateway.strikes(eq("SPY"), eq(expiration), eq(OptionType.CALL))).thenReturn(available);

        assertEquals(available, service.strikes("SPY", expiration, OptionType.CALL));
    }

    @Test
    void retrievesAvailableStrikesForSameDayExpiration() {
        LocalDate today = LocalDate.now();
        List<BigDecimal> available = List.of(new BigDecimal("95"), new BigDecimal("100"));
        when(gateway.strikes(eq("SPY"), eq(today), eq(OptionType.CALL))).thenReturn(available);

        assertEquals(available, service.strikes("SPY", today, OptionType.CALL));
    }

    @Test
    void calculatesDebitPositionBidAndAskFromLegQuotes() {
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(longSymbol, new BigDecimal("1.00"), new BigDecimal("1.20"), 231),
                new OptionQuote(shortSymbol, new BigDecimal("0.40"), new BigDecimal("0.60"))));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.VERTICAL,
                List.of(longLeg, shortLeg), OrderType.DEBIT));

        assertEquals(new BigDecimal("0.40"), quote.bid());
        assertEquals(new BigDecimal("0.80"), quote.ask());
        assertEquals(2, quote.legs().size());
        assertEquals(231, quote.legs().getFirst().openInterest());
    }

    @Test
    void calculatesCreditPositionBidAndAskFromLegQuotes() {
        OptionLeg shortLeg = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("100"),
                OpeningAction.SELL_TO_OPEN, 1);
        OptionLeg longCall = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("105"),
                OpeningAction.BUY_TO_OPEN, 1);
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", shortLeg),
                        new BigDecimal("1.00"), new BigDecimal("1.20")),
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", longCall),
                        new BigDecimal("0.40"), new BigDecimal("0.60"))));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.VERTICAL,
                List.of(shortLeg, longCall), OrderType.CREDIT));

        assertEquals(new BigDecimal("0.40"), quote.bid());
        assertEquals(new BigDecimal("0.80"), quote.ask());
    }

    @Test
    void calculatesAndReturnsGreeksPerContract() {
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(longSymbol, new BigDecimal("1.00"), new BigDecimal("1.20"),
                        231, new BigDecimal("0.60"), new BigDecimal("0.02"),
                        new BigDecimal("-0.04"), new BigDecimal("0.10"), new BigDecimal("0.03"),
                        new BigDecimal("0.24")),
                new OptionQuote(shortSymbol, new BigDecimal("0.40"), new BigDecimal("0.60"),
                        42, new BigDecimal("0.35"), new BigDecimal("0.015"),
                        new BigDecimal("-0.03"), new BigDecimal("0.08"), new BigDecimal("0.02"),
                        new BigDecimal("0.26"))));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.VERTICAL,
                List.of(longLeg, shortLeg), OrderType.DEBIT));

        assertEquals(new BigDecimal("25.00"), quote.positionDelta());
        assertEquals(new BigDecimal("0.500"), quote.positionGamma());
        assertEquals(new BigDecimal("-1.00"), quote.positionTheta());
        assertEquals(new BigDecimal("2.00"), quote.positionVega());
        assertEquals(new BigDecimal("1.00"), quote.positionRho());
        assertEquals(new BigDecimal("60.00"), quote.legs().getFirst().delta());
        assertEquals(new BigDecimal("2.00"), quote.legs().getFirst().gamma());
        assertEquals(new BigDecimal("-4.00"), quote.legs().getFirst().theta());
        assertEquals(new BigDecimal("10.00"), quote.legs().getFirst().vega());
        assertEquals(new BigDecimal("3.00"), quote.legs().getFirst().rho());
        assertEquals("TRADIER", quote.legs().getFirst().greekSource());
    }

    @Test
    void estimatesMissingGreeksAndImpliedVolatilityFromOptionMidpoint() {
        OptionLeg call = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("100"),
                OpeningAction.BUY_TO_OPEN, 1);
        when(gateway.underlyingPrice("SPY")).thenReturn(new BigDecimal("100"));
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", call),
                        new BigDecimal("2.80"), new BigDecimal("2.90"), 1234)));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.SINGLE_LEG,
                List.of(call), OrderType.DEBIT));

        TradeQuoteService.LegQuote legQuote = quote.legs().getFirst();
        assertEquals("MODEL_ESTIMATE", legQuote.greekSource());
        assertEquals(1234, legQuote.openInterest());
        assertNotNull(legQuote.impliedVolatility());
        assertTrue(legQuote.impliedVolatility().compareTo(new BigDecimal("0.10")) > 0);
        assertTrue(legQuote.impliedVolatility().compareTo(BigDecimal.ONE) < 0);
        assertNotNull(legQuote.delta());
        assertTrue(legQuote.delta().compareTo(new BigDecimal("45")) > 0);
        assertTrue(legQuote.delta().compareTo(new BigDecimal("55")) < 0);
        assertNotNull(legQuote.gamma());
        assertTrue(legQuote.gamma().compareTo(BigDecimal.ONE) > 0);
        assertNotNull(legQuote.theta());
        assertTrue(legQuote.theta().compareTo(BigDecimal.ONE.negate()) < 0);
        assertNotNull(legQuote.vega());
        assertTrue(legQuote.vega().compareTo(BigDecimal.ONE) > 0);
        assertNotNull(legQuote.rho());
        assertTrue(legQuote.rho().compareTo(BigDecimal.ONE) > 0);
        assertNotNull(quote.positionDelta());
        assertNotNull(quote.positionGamma());
        assertNotNull(quote.positionTheta());
        assertNotNull(quote.positionVega());
        assertNotNull(quote.positionRho());
        assertEquals(legQuote.delta(), quote.positionDelta());
        assertEquals(legQuote.gamma(), quote.positionGamma());
        assertEquals(legQuote.theta(), quote.positionTheta());
        assertEquals(legQuote.vega(), quote.positionVega());
        assertEquals(legQuote.rho(), quote.positionRho());
    }

    @Test
    void keepsPositionBidAndAskPerSpreadUnitForMultipleContracts() {
        OptionLeg longThree = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("100"),
                OpeningAction.BUY_TO_OPEN, 3);
        OptionLeg shortThree = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("105"),
                OpeningAction.SELL_TO_OPEN, 3);
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", longThree),
                        new BigDecimal("1.00"), new BigDecimal("1.20")),
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", shortThree),
                        new BigDecimal("0.40"), new BigDecimal("0.60"))));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.VERTICAL,
                List.of(longThree, shortThree), OrderType.DEBIT));

        assertEquals(new BigDecimal("0.40"), quote.bid());
        assertEquals(new BigDecimal("0.80"), quote.ask());
    }

    @Test
    void pricesAnUnbalancedPositionUsingItsLegRatio() {
        OptionLeg twoLong = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("100"),
                OpeningAction.BUY_TO_OPEN, 2);
        OptionLeg oneShort = new OptionLeg(OptionType.CALL, expiration, new BigDecimal("105"),
                OpeningAction.SELL_TO_OPEN, 1);
        when(gateway.quotes(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", twoLong),
                        new BigDecimal("1.00"), new BigDecimal("1.20")),
                new OptionQuote(TradierSandboxClient.optionSymbol("SPY", oneShort),
                        new BigDecimal("0.40"), new BigDecimal("0.60"))));

        TradeQuoteService.PositionQuote quote = service.quote(new TradeQuoteRequest("SPY", SpreadType.VERTICAL,
                List.of(twoLong, oneShort), OrderType.DEBIT));

        assertEquals(new BigDecimal("1.40"), quote.bid());
        assertEquals(new BigDecimal("2.00"), quote.ask());
    }

    @Test
    void rejectsInvalidSpreadBeforeRequestingQuotes() {
        assertThrows(IllegalArgumentException.class, () -> service.quote(new TradeQuoteRequest("SPY",
                SpreadType.VERTICAL, List.of(longLeg), OrderType.DEBIT)));
    }

    @Test
    void rejectsEvenOrdersBecauseTheyHaveNoBidAskLimitPrice() {
        assertThrows(IllegalArgumentException.class, () -> service.quote(new TradeQuoteRequest("SPY",
                SpreadType.VERTICAL, List.of(longLeg, shortLeg), OrderType.EVEN)));
    }
}
