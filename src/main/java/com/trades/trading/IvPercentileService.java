package com.trades.trading;

import com.trades.persistence.IvHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Service
public class IvPercentileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IvPercentileService.class);
    private static final ZoneId MARKET_TIME_ZONE = ZoneId.of("America/New_York");
    private static final int REQUIRED_OBSERVATIONS = 252;
    private static final int COMPARISON_OBSERVATIONS = REQUIRED_OBSERVATIONS - 1;

    private final IvHistoryMapper historyMapper;
    private final TradierGateway gateway;

    public IvPercentileService(IvHistoryMapper historyMapper, TradierGateway gateway) {
        this.historyMapper = historyMapper;
        this.gateway = gateway;
    }

    public IvPercentile percentile(String requestedSymbol) {
        if (requestedSymbol == null || !requestedSymbol.matches("[A-Za-z][A-Za-z0-9.-]{0,9}")) {
            throw new IllegalArgumentException("Enter a valid underlying symbol to load its IV percentile.");
        }
        String symbol = requestedSymbol.toUpperCase(Locale.ROOT);
        historyMapper.trackSymbol(symbol);
        LocalDate today = LocalDate.now(MARKET_TIME_ZONE);
        BigDecimal currentIv = historyMapper.findSample(symbol, today);
        if (currentIv == null) currentIv = capture(symbol, today);
        List<BigDecimal> historicalIvs = historicalSamples(symbol, today);
        BigDecimal percentile = percentileRank(currentIv, historicalIvs);
        int observationCount = Math.min(historicalIvs.size() + 1, REQUIRED_OBSERVATIONS);
        return new IvPercentile(symbol, currentIv, percentile, observationCount,
                REQUIRED_OBSERVATIONS, observationCount == REQUIRED_OBSERVATIONS);
    }

    @Scheduled(cron = "0 15 16 * * MON-FRI", zone = "America/New_York")
    public void collectDailySamples() {
        LocalDate today = LocalDate.now(MARKET_TIME_ZONE);
        for (String symbol : historyMapper.findTrackedSymbols()) {
            try {
                capture(symbol, today);
            } catch (RestClientException | TradierRequestException | ResponseStatusException exception) {
                LOGGER.warn("Unable to collect daily IV sample for {}: {}", symbol, exception.getMessage());
            }
        }
    }

    private BigDecimal capture(String symbol, LocalDate observationDate) {
        BigDecimal impliedVolatility = gateway.underlyingThirtyDayAtmIv(symbol);
        if (impliedVolatility == null || impliedVolatility.signum() <= 0
                || impliedVolatility.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw new TradierRequestException("Tradier returned an unusable 30-day ATM implied volatility.");
        }
        historyMapper.saveSample(symbol, observationDate, impliedVolatility);
        return impliedVolatility;
    }

    private List<BigDecimal> historicalSamples(String symbol, LocalDate today) {
        return historyMapper.findHistoricalSamples(
                symbol, today.minusWeeks(52), today, COMPARISON_OBSERVATIONS);
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
