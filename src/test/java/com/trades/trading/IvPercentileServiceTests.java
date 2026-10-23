package com.trades.trading;

import com.trades.persistence.IvHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IvPercentileServiceTests {
    private IvHistoryMapper historyMapper;
    private TradierGateway gateway;
    private IvPercentileService service;

    @BeforeEach
    void setUp() {
        historyMapper = mock(IvHistoryMapper.class);
        gateway = mock(TradierGateway.class);
        service = new IvPercentileService(historyMapper, gateway);
    }

    @Test
    void collectsTodaysSampleAndReportsHistoryAsItBuilds() {
        when(gateway.underlyingThirtyDayAtmIv("SPY")).thenReturn(new BigDecimal("0.250000"));
        when(historyMapper.findSample(any(), any())).thenReturn(null);
        when(historyMapper.findHistoricalSamples(any(), any(), any(), anyInt())).thenReturn(List.of());

        IvPercentileService.IvPercentile result = service.percentile("spy");

        assertThat(result.symbol()).isEqualTo("SPY");
        assertThat(result.currentIv()).isEqualByComparingTo("0.25");
        assertThat(result.percentile()).isNull();
        assertThat(result.observationCount()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        verify(historyMapper).trackSymbol("SPY");
        verify(historyMapper).saveSample(any(), any(), any());
        verify(gateway).underlyingThirtyDayAtmIv("SPY");
    }

    @Test
    void calculatesPercentileFromPriorSamplesAndDoesNotRecaptureSameDay() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/New_York"));
        when(historyMapper.findSample("SPY", today)).thenReturn(null);
        when(historyMapper.findHistoricalSamples("SPY", today.minusWeeks(52), today, 251))
                .thenReturn(List.of(new BigDecimal("0.200000")));
        when(gateway.underlyingThirtyDayAtmIv("SPY")).thenReturn(new BigDecimal("0.300000"));

        IvPercentileService.IvPercentile result = service.percentile("SPY");

        assertThat(result.percentile()).isEqualByComparingTo("100.0");
        assertThat(result.observationCount()).isEqualTo(2);
        assertThat(result.complete()).isFalse();
        verify(historyMapper).saveSample("SPY", today, new BigDecimal("0.300000"));
        verify(gateway).underlyingThirtyDayAtmIv("SPY");
    }
}
