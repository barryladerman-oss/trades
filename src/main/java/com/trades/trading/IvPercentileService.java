package com.trades.trading;

import com.trades.persistence.IvHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
public class IvPercentileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IvPercentileService.class);
    private static final ZoneId MARKET_TIME_ZONE = ZoneId.of("America/New_York");
    private static final int REQUIRED_OBSERVATIONS = 252;
    private static final int COMPARISON_OBSERVATIONS = REQUIRED_OBSERVATIONS - 1;
    private static final LocalTime COLLECTION_TIME = LocalTime.of(16, 15);

    private final IvHistoryMapper historyMapper;
    private final TradierGateway gateway;
    private final TradierEnvironment environmentSource;
    private final Clock clock;

    @Autowired
    public IvPercentileService(IvHistoryMapper historyMapper, TradierGateway gateway,
                               TradierEnvironment environmentSource) {
        this(historyMapper, gateway, environmentSource, Clock.system(MARKET_TIME_ZONE));
    }

    IvPercentileService(IvHistoryMapper historyMapper, TradierGateway gateway,
                        TradierEnvironment environmentSource, Clock clock) {
        this.historyMapper = historyMapper;
        this.gateway = gateway;
        this.environmentSource = environmentSource;
        this.clock = clock;
    }

    public IvPercentile percentile(String requestedSymbol) {
        if (requestedSymbol == null || !requestedSymbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol to load its IV percentile.");
        }
        String symbol = requestedSymbol.toUpperCase(Locale.ROOT);
        String environment = environmentSource.environment();
        historyMapper.trackSymbol(symbol);
        LocalDate today = LocalDate.now(clock);
        BigDecimal currentIv = historyMapper.findSample(symbol, today, environment);
        if (currentIv == null) currentIv = capture(symbol, today, environment);
        List<BigDecimal> historicalIvs = historicalSamples(symbol, today, environment);
        BigDecimal percentile = percentileRank(currentIv, historicalIvs);
        int observationCount = Math.min(historicalIvs.size() + 1, REQUIRED_OBSERVATIONS);
        return new IvPercentile(symbol, currentIv, percentile, observationCount,
                REQUIRED_OBSERVATIONS, observationCount == REQUIRED_OBSERVATIONS);
    }

    @Scheduled(cron = "0 15 16 * * MON-FRI", zone = "America/New_York")
    public void collectDailySamples() {
        collectSamples(LocalDate.now(clock), false);
    }

    /**
     * Fills in today's sample for any tracked symbol that missed the 4:15 PM collection, for example
     * because the app was not running. Runs at startup and hourly; does nothing before the close or on weekends.
     */
    @Scheduled(initialDelayString = "${trades.iv-catch-up.initial-delay-ms:30000}",
            fixedDelayString = "${trades.iv-catch-up.interval-ms:3600000}")
    public void collectMissingSamples() {
        LocalDateTime now = LocalDateTime.now(clock);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || now.toLocalTime().isBefore(COLLECTION_TIME)) {
            return;
        }
        collectSamples(now.toLocalDate(), true);
    }

    private void collectSamples(LocalDate date, boolean missingOnly) {
        String environment = environmentSource.environment();
        for (String symbol : historyMapper.findTrackedSymbols()) {
            try {
                if (missingOnly && historyMapper.findSample(symbol, date, environment) != null) {
                    continue;
                }
                capture(symbol, date, environment);
            } catch (RestClientException | TradierRequestException | ResponseStatusException
                     | UnsupportedOperationException exception) {
                LOGGER.warn("Unable to collect daily IV sample for {}: {}", symbol, exception.getMessage());
            }
        }
    }

    private BigDecimal capture(String symbol, LocalDate observationDate, String environment) {
        BigDecimal impliedVolatility = gateway.underlyingThirtyDayAtmIv(symbol);
        if (impliedVolatility == null || impliedVolatility.signum() <= 0
                || impliedVolatility.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw new TradierRequestException("Tradier returned an unusable 30-day ATM implied volatility.");
        }
        historyMapper.saveSample(symbol, observationDate, impliedVolatility, environment);
        return impliedVolatility;
    }

    private List<BigDecimal> historicalSamples(String symbol, LocalDate today, String environment) {
        return historyMapper.findHistoricalSamples(
                symbol, today.minusWeeks(52), today, COMPARISON_OBSERVATIONS, environment);
    }

    private BigDecimal percentileRank(BigDecimal currentIv, List<BigDecimal> historicalIvs) {
        if (historicalIvs.isEmpty()) {
            return null;
        }
        long below = historicalIvs.stream().filter(iv -> iv.compareTo(currentIv) < 0).count();
        long equal = historicalIvs.stream().filter(iv -> iv.compareTo(currentIv) == 0).count();
        return BigDecimal.valueOf(below)
                .add(BigDecimal.valueOf(equal).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(historicalIvs.size()), 1, RoundingMode.HALF_UP);
    }

    public record IvPercentile(String symbol, BigDecimal currentIv, BigDecimal percentile,
                               int observationCount, int requiredObservations, boolean complete) {
    }
}
