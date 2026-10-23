package com.trades.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradePreviewServiceTests {
    private final TradeValidator validator = new TradeValidator();
    private final TradierGateway gateway = mock(TradierGateway.class);
    private final TradePreviewService service = new TradePreviewService(validator, gateway);
    private final TradeRequest trade = new TradeRequest("SPY", SpreadType.SINGLE_LEG,
            List.of(new OptionLeg(OptionType.CALL, LocalDate.now().plusDays(30),
                    new BigDecimal("500"), OpeningAction.BUY_TO_OPEN, 1)),
            OrderType.DEBIT, new BigDecimal("1.00"));

    @Test
    void submissionRequiresPreviewSandboxAndExplicitConfirmationAndConsumesTicket() throws Exception {
        when(gateway.preview(trade)).thenReturn(new ObjectMapper().readTree("{\"preview\":true}"));
        when(gateway.submit(trade)).thenReturn(new ObjectMapper().readTree("{\"id\":\"paper-1\"}"));

        String previewId = service.preview(trade).previewId();
        assertThrows(IllegalArgumentException.class, () -> service.submit(previewId, "LIVE", true));
        assertThrows(IllegalArgumentException.class, () -> service.submit(previewId, "SANDBOX", false));

        service.submit(previewId, "SANDBOX", true);
        assertThrows(IllegalArgumentException.class, () -> service.submit(previewId, "SANDBOX", true));
        verify(gateway).submit(trade);
    }

    @Test
    void invalidTradeNeverCallsBroker() {
        TradeRequest invalid = new TradeRequest("SPY", SpreadType.SINGLE_LEG, List.of(), OrderType.DEBIT,
                BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class, () -> service.preview(invalid));
        verify(gateway, never()).preview(invalid);
    }
}
