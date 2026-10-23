package com.trades.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TradeQuoteService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TradeQuoteService.class);
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final BigDecimal CONTRACT_MULTIPLIER = BigDecimal.valueOf(100);
    private static final double MIN_VOLATILITY = 0.0001;
    private static final double MAX_VOLATILITY = 5.0;

    private final TradeValidator validator;
    private final TradierGateway gateway;

    public TradeQuoteService(TradeValidator validator, TradierGateway gateway) {
        this.validator = validator;
        this.gateway = gateway;
    }

    public List<SymbolSuggestion> symbols(String query) {
        if (query == null || !query.trim().matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter at least the first letter of a valid underlying symbol.");
        }
        return gateway.symbols(query.trim().toUpperCase(Locale.ROOT));
    }

    public List<LocalDate> expirations(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol before selecting expirations.");
        }
        return gateway.expirations(symbol);
    }

    public List<BigDecimal> strikes(String symbol, LocalDate expiration, OptionType optionType) {
        if (symbol == null || !symbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol before selecting strikes.");
        }
        if (expiration == null || expiration.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Option expiration cannot be in the past.");
        }
        if (optionType == null) {
            throw new IllegalArgumentException("Choose an option type before selecting strikes.");
        }
        return gateway.strikes(symbol, expiration, optionType);
    }

    public BigDecimal underlyingPrice(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol before selecting a center strike.");
        }
        return gateway.underlyingPrice(symbol);
    }

    public UnderlyingQuote underlyingQuote(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol to load its quote.");
        }
        return gateway.underlyingQuote(symbol);
    }

    public List<UnderlyingBar> underlyingTimesales(String symbol) {
        if (symbol == null || !symbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol to load its intraday chart.");
        }
        return gateway.underlyingTimesales(symbol);
    }

    public List<UnderlyingBar> underlyingTimesales(String symbol, int intervalMinutes) {
        if (!List.of(1, 5, 15, 30, 60).contains(intervalMinutes)) {
            throw new IllegalArgumentException("Choose a candle interval of 1, 5, 15, 30, or 60 minutes.");
        }
        List<UnderlyingBar> minuteBars = underlyingTimesales(symbol);
        if (intervalMinutes == 1) {
            return minuteBars;
        }
        java.util.Map<Integer, List<UnderlyingBar>> buckets = new java.util.TreeMap<>();
        for (UnderlyingBar bar : minuteBars) {
            java.time.LocalTime time = java.time.LocalDateTime.parse(bar.time()).toLocalTime();
            int minutesSinceOpen = time.getHour() * 60 + time.getMinute() - (9 * 60 + 30);
            buckets.computeIfAbsent(Math.floorDiv(minutesSinceOpen, intervalMinutes),
                    ignored -> new java.util.ArrayList<>()).add(bar);
        }
        return buckets.values().stream().map(group -> {
            List<UnderlyingBar> ordered = group.stream()
                    .sorted(java.util.Comparator.comparing(UnderlyingBar::time)).toList();
            return new UnderlyingBar(
                    ordered.getFirst().time(),
                    ordered.getFirst().open(),
                    ordered.stream().map(UnderlyingBar::high).max(BigDecimal::compareTo).orElseThrow(),
                    ordered.stream().map(UnderlyingBar::low).min(BigDecimal::compareTo).orElseThrow(),
                    ordered.getLast().close(),
                    ordered.stream().mapToLong(UnderlyingBar::volume).sum());
        }).toList();
    }

    public PositionQuote quote(TradeQuoteRequest request) {
        if (request.orderType() == OrderType.EVEN) {
            throw new IllegalArgumentException(
                    "Choose a debit or credit order to calculate its bid/ask. Even orders always have a zero limit.");
        }
        OrderType validationOrderType = request.orderType();
        if (request.spreadType() == SpreadType.SINGLE_LEG && request.legs() != null
                && request.legs().size() == 1 && request.legs().getFirst() != null) {
            validationOrderType = request.legs().getFirst().action() == OpeningAction.BUY_TO_OPEN
                    ? OrderType.DEBIT : OrderType.CREDIT;
        }
        BigDecimal validationPrice = validationOrderType == OrderType.EVEN
                ? BigDecimal.ZERO : CENT;
        validator.validate(new TradeRequest(request.symbol(), request.spreadType(), request.legs(),
                validationOrderType, validationPrice));

        List<OptionQuote> quotes = gateway.quotes(new TradeRequest(request.symbol(), request.spreadType(),
                request.legs(), validationOrderType, validationPrice));
        boolean needsEstimatedGreeks = quotes.stream().anyMatch(TradeQuoteService::hasMissingGreeks);
        BigDecimal underlying = null;
        if (needsEstimatedGreeks) {
            try {
                underlying = gateway.underlyingPrice(request.symbol());
            } catch (TradierRequestException | RestClientException exception) {
                LOGGER.warn("Unable to retrieve underlying price for missing option Greek estimates: {}",
                        exception.getMessage());
            }
        }
        Map<String, OptionQuote> bySymbol = new HashMap<>();
        for (OptionQuote quote : quotes) {
            bySymbol.put(quote.optionSymbol(), quote);
        }

        BigDecimal bid = BigDecimal.ZERO;
        BigDecimal ask = BigDecimal.ZERO;
        BigDecimal positionDelta = BigDecimal.ZERO;
        BigDecimal positionGamma = BigDecimal.ZERO;
        BigDecimal positionTheta = BigDecimal.ZERO;
        BigDecimal positionVega = BigDecimal.ZERO;
        BigDecimal positionRho = BigDecimal.ZERO;
        boolean hasDelta = true;
        boolean hasGamma = true;
        boolean hasTheta = true;
        boolean hasVega = true;
        boolean hasRho = true;
        int contractsPerUnit = request.legs().stream()
                .mapToInt(OptionLeg::quantity)
                .reduce(0, TradeQuoteService::greatestCommonDivisor);
        List<LegQuote> legQuotes = new ArrayList<>();
        for (OptionLeg leg : request.legs()) {
            String symbol = TradierSandboxClient.optionSymbol(request.symbol(), leg);
            OptionQuote quote = bySymbol.get(symbol);
            if (quote == null) {
                throw new TradierRequestException("Tradier did not return a bid and ask for every option leg.");
            }
            GreekValues estimate = underlying == null ? null : estimateGreeks(quote, leg, underlying);
            BigDecimal impliedVolatility = quote.impliedVolatility() != null
                    ? quote.impliedVolatility() : estimate == null ? null : estimate.impliedVolatility();
            BigDecimal delta = quote.delta() != null ? quote.delta() : estimate == null ? null : estimate.delta();
            BigDecimal gamma = quote.gamma() != null ? quote.gamma() : estimate == null ? null : estimate.gamma();
            BigDecimal theta = quote.theta() != null ? quote.theta() : estimate == null ? null : estimate.theta();
            BigDecimal vega = quote.vega() != null ? quote.vega() : estimate == null ? null : estimate.vega();
            BigDecimal rho = quote.rho() != null ? quote.rho() : estimate == null ? null : estimate.rho();
            String greekSource = hasCompleteGreeks(quote) ? "TRADIER"
                    : estimate != null && estimate.impliedVolatility() != null ? "MODEL_ESTIMATE"
                    : hasAnyGreeks(quote) ? "PARTIAL" : "UNAVAILABLE";
            BigDecimal quantity = BigDecimal.valueOf(leg.quantity());
            BigDecimal quoteQuantity = quantity.divide(BigDecimal.valueOf(contractsPerUnit));
            boolean longLeg = leg.action() == OpeningAction.BUY_TO_OPEN;
            BigDecimal legBid = longLeg ? quote.bid() : quote.ask().negate();
            BigDecimal legAsk = longLeg ? quote.ask() : quote.bid().negate();
            bid = bid.add(legBid.multiply(quoteQuantity));
            ask = ask.add(legAsk.multiply(quoteQuantity));
            BigDecimal greekSign = longLeg ? BigDecimal.ONE : BigDecimal.ONE.negate();
            BigDecimal greekQuantity = quantity.multiply(CONTRACT_MULTIPLIER).multiply(greekSign);
            if (delta == null) {
                hasDelta = false;
            } else {
                positionDelta = positionDelta.add(delta.multiply(greekQuantity));
            }
            if (gamma == null) {
                hasGamma = false;
            } else {
                positionGamma = positionGamma.add(gamma.multiply(greekQuantity));
            }
            if (theta == null) {
                hasTheta = false;
            } else {
                positionTheta = positionTheta.add(theta.multiply(greekQuantity));
            }
            if (vega == null) {
                hasVega = false;
            } else {
                positionVega = positionVega.add(vega.multiply(greekQuantity));
            }
            if (rho == null) {
                hasRho = false;
            } else {
                positionRho = positionRho.add(rho.multiply(greekQuantity));
            }
            legQuotes.add(new LegQuote(symbol, leg.action(), leg.quantity(),
                    quote.bid(), quote.ask(), quote.openInterest(), perContract(delta), perContract(gamma),
                    perContract(theta), perContract(vega), perContract(rho), impliedVolatility, greekSource));
        }

        if (validationOrderType == OrderType.CREDIT) {
            BigDecimal creditBid = ask.negate();
            BigDecimal creditAsk = bid.negate();
            bid = creditBid;
            ask = creditAsk;
        }
        if (bid.signum() < 0 || ask.signum() < 0 || bid.compareTo(ask) > 0) {
            throw new TradierRequestException(
                    "The selected order type does not produce a valid non-negative bid/ask for these legs.");
        }

        return new PositionQuote("TRADIER_SANDBOX", bid.setScale(2, RoundingMode.HALF_UP),
                ask.setScale(2, RoundingMode.HALF_UP), Instant.now(), legQuotes,
                hasDelta ? positionDelta : null, hasGamma ? positionGamma : null,
                hasTheta ? positionTheta : null, hasVega ? positionVega : null,
                hasRho ? positionRho : null);
    }

    private static int greatestCommonDivisor(int first, int second) {
        int left = Math.abs(first);
        int right = Math.abs(second);
        while (right != 0) {
            int remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    private static BigDecimal perContract(BigDecimal greek) {
        return greek == null ? null : greek.multiply(CONTRACT_MULTIPLIER);
    }

    private static boolean hasMissingGreeks(OptionQuote quote) {
        return !hasCompleteGreeks(quote);
    }

    private static boolean hasCompleteGreeks(OptionQuote quote) {
        return quote.delta() != null && quote.gamma() != null && quote.theta() != null
                && quote.vega() != null && quote.rho() != null && quote.impliedVolatility() != null;
    }

    private static boolean hasAnyGreeks(OptionQuote quote) {
        return quote.delta() != null || quote.gamma() != null || quote.theta() != null
                || quote.vega() != null || quote.rho() != null || quote.impliedVolatility() != null;
    }

    private static GreekValues estimateGreeks(OptionQuote quote, OptionLeg leg, BigDecimal underlying) {
        double spot = underlying.doubleValue();
        double strike = leg.strike().doubleValue();
        double time = Math.max(1, ChronoUnit.DAYS.between(LocalDate.now(), leg.expiration())) / 365.0;
        if (!Double.isFinite(spot) || spot <= 0 || !Double.isFinite(strike) || strike <= 0) return null;
        double volatility = quote.impliedVolatility() == null
                ? impliedVolatility(quote, leg.optionType(), spot, strike, time)
                : quote.impliedVolatility().doubleValue();
        if (!Double.isFinite(volatility) || volatility <= 0) return null;
        double rootTime = Math.sqrt(time);
        double deviation = volatility * rootTime;
        double d1 = (Math.log(spot / strike) + deviation * deviation / 2) / deviation;
        double d2 = d1 - deviation;
        double density = Math.exp(-d1 * d1 / 2) / Math.sqrt(2 * Math.PI);
        double delta = leg.optionType() == OptionType.CALL
                ? normalCumulativeProbability(d1) : normalCumulativeProbability(d1) - 1;
        double gamma = density / (spot * deviation);
        double theta = -(spot * density * volatility) / (2 * rootTime * 365);
        double vega = spot * density * rootTime * 0.01;
        double rho = leg.optionType() == OptionType.CALL
                ? strike * time * normalCumulativeProbability(d2) * 0.01
                : -strike * time * normalCumulativeProbability(-d2) * 0.01;
        return new GreekValues(decimal(delta), decimal(gamma), decimal(theta), decimal(vega), decimal(rho),
                decimal(volatility));
    }

    private static double impliedVolatility(OptionQuote quote, OptionType type,
                                            double spot, double strike, double time) {
        double marketPrice = (quote.bid().doubleValue() + quote.ask().doubleValue()) / 2;
        double intrinsic = type == OptionType.CALL
                ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0);
        double maximum = type == OptionType.CALL ? spot : strike;
        if (!Double.isFinite(marketPrice) || marketPrice <= 0
                || marketPrice < intrinsic - 0.02 || marketPrice >= maximum) return Double.NaN;
        double low = MIN_VOLATILITY;
        double high = MAX_VOLATILITY;
        if (optionPrice(type, spot, strike, time, high) < marketPrice) return Double.NaN;
        for (int iteration = 0; iteration < 100; iteration++) {
            double middle = (low + high) / 2;
            if (optionPrice(type, spot, strike, time, middle) < marketPrice) low = middle;
            else high = middle;
        }
        return (low + high) / 2;
    }

    private static double optionPrice(OptionType type, double spot, double strike,
                                      double time, double volatility) {
        double deviation = volatility * Math.sqrt(time);
        double d1 = (Math.log(spot / strike) + deviation * deviation / 2) / deviation;
        double d2 = d1 - deviation;
        return type == OptionType.CALL
                ? spot * normalCumulativeProbability(d1) - strike * normalCumulativeProbability(d2)
                : strike * normalCumulativeProbability(-d2) - spot * normalCumulativeProbability(-d1);
    }

    private static double normalCumulativeProbability(double value) {
        double sign = value < 0 ? -1 : 1;
        double x = Math.abs(value) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double polynomial = 1.061405429 * t - 1.453152027;
        polynomial = polynomial * t + 1.421413741;
        polynomial = polynomial * t - 0.284496736;
        polynomial = polynomial * t + 0.254829592;
        polynomial *= t;
        double errorFunction = sign * (1 - polynomial * Math.exp(-x * x));
        return (1 + errorFunction) / 2;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }

    private record GreekValues(BigDecimal delta, BigDecimal gamma, BigDecimal theta,
                               BigDecimal vega, BigDecimal rho, BigDecimal impliedVolatility) {
    }

    public record LegQuote(String optionSymbol, OpeningAction action, int quantity,
                           BigDecimal bid, BigDecimal ask, Integer openInterest, BigDecimal delta,
                           BigDecimal gamma, BigDecimal theta, BigDecimal vega, BigDecimal rho,
                           BigDecimal impliedVolatility, String greekSource) {
    }

    public record PositionQuote(String source, BigDecimal bid, BigDecimal ask,
                                Instant quotedAt, List<LegQuote> legs, BigDecimal positionDelta,
                                BigDecimal positionGamma, BigDecimal positionTheta,
                                BigDecimal positionVega, BigDecimal positionRho) {
    }
}
