package com.trades.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeValidatorTests {
    private final TradeValidator validator = new TradeValidator();
    private final LocalDate near = LocalDate.now().plusDays(30);
    private final LocalDate far = LocalDate.now().plusDays(60);

    @Test
    void acceptsAllSupportedStructures() {
        assertValid(SpreadType.SINGLE_LEG, leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.VERTICAL,
                leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1));
        assertValid(SpreadType.VERTICAL,
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, near, 95, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.VERTICAL,
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.VERTICAL,
                leg(OptionType.PUT, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 95, OpeningAction.SELL_TO_OPEN, 1));
        assertValid(SpreadType.IRON_CONDOR,
                leg(OptionType.PUT, near, 90, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 95, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.BUTTERFLY,
                leg(OptionType.CALL, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 2),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.CALENDAR,
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, far, 100, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.DIAGONAL,
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, far, 105, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.DOUBLE_DIAGONAL,
                leg(OptionType.PUT, near, 90, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, far, 85, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, far, 115, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.IRON_BUTTERFLY,
                leg(OptionType.PUT, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 1));
    }

    @Test
    void acceptsSameDayExpirationsAndRejectsPastExpirations() {
        assertValid(SpreadType.SINGLE_LEG,
                leg(OptionType.CALL, LocalDate.now(), 100, OpeningAction.BUY_TO_OPEN, 1));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.SINGLE_LEG,
                leg(OptionType.CALL, LocalDate.now().minusDays(1), 100, OpeningAction.BUY_TO_OPEN, 1)));
    }

    @Test
    void rejectsMissingTrade() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
    }

    @Test
    void rejectsMalformedStructuresAndOrderPrices() {
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.VERTICAL,
                leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 105, OpeningAction.SELL_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.BUTTERFLY,
                leg(OptionType.CALL, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 106, OpeningAction.BUY_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.CALENDAR,
                leg(OptionType.PUT, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, far, 100, OpeningAction.SELL_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TradeRequest(
                "SPY", SpreadType.SINGLE_LEG,
                List.of(leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 1)),
                OrderType.DEBIT, new BigDecimal("0.00"))));
    }

    @Test
    void rejectsDoubleDiagonalWithDifferentSharedExpirations() {
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.DOUBLE_DIAGONAL,
                leg(OptionType.PUT, near, 90, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, far, 85, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, far.plusDays(7), 115, OpeningAction.BUY_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.DOUBLE_DIAGONAL,
                leg(OptionType.PUT, near, 95, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, far, 90, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, far, 105, OpeningAction.BUY_TO_OPEN, 1)));
    }

    @Test
    void acceptsUserOverriddenLegQuantities() {
        assertValid(SpreadType.VERTICAL,
                leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 2),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1));
        assertValid(SpreadType.IRON_CONDOR,
                leg(OptionType.PUT, near, 90, OpeningAction.BUY_TO_OPEN, 2),
                leg(OptionType.PUT, near, 95, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 3),
                leg(OptionType.CALL, near, 115, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.BUTTERFLY,
                leg(OptionType.CALL, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 3),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 2));
        assertValid(SpreadType.CALENDAR,
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 2),
                leg(OptionType.PUT, far, 100, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.DIAGONAL,
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 2),
                leg(OptionType.CALL, far, 105, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.DOUBLE_DIAGONAL,
                leg(OptionType.PUT, near, 90, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, far, 85, OpeningAction.BUY_TO_OPEN, 2),
                leg(OptionType.CALL, near, 110, OpeningAction.SELL_TO_OPEN, 3),
                leg(OptionType.CALL, far, 115, OpeningAction.BUY_TO_OPEN, 1));
        assertValid(SpreadType.IRON_BUTTERFLY,
                leg(OptionType.PUT, near, 90, OpeningAction.BUY_TO_OPEN, 2),
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 3),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 2));
    }

    @Test
    void rejectsIronCondorWithLongInnerStrikesOrCrossedSides() {
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.IRON_CONDOR,
                leg(OptionType.PUT, near, 90, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.BUY_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.IRON_CONDOR,
                leg(OptionType.PUT, near, 90, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 106, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 110, OpeningAction.BUY_TO_OPEN, 1)));
    }

    @Test
    void rejectsDiagonalWithNearerLongOrLongStrikeCloserToTheMoney() {
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.DIAGONAL,
                leg(OptionType.CALL, far, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.DIAGONAL,
                leg(OptionType.CALL, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, far, 95, OpeningAction.BUY_TO_OPEN, 1)));
    }

    @Test
    void rejectsIronButterflyWithoutSharedCenterOrLongPutAndShortCallVerticals() {
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.IRON_BUTTERFLY,
                leg(OptionType.PUT, near, 95, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.PUT, near, 100, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 101, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.BUY_TO_OPEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> validate(SpreadType.IRON_BUTTERFLY,
                leg(OptionType.PUT, near, 95, OpeningAction.SELL_TO_OPEN, 1),
                leg(OptionType.PUT, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 100, OpeningAction.BUY_TO_OPEN, 1),
                leg(OptionType.CALL, near, 105, OpeningAction.SELL_TO_OPEN, 1)));
    }

    private void assertValid(SpreadType type, OptionLeg... legs) {
        assertDoesNotThrow(() -> validate(type, legs));
    }

    private void validate(SpreadType type, OptionLeg... legs) {
        validator.validate(new TradeRequest("SPY", type, List.of(legs), OrderType.DEBIT, new BigDecimal("1.00")));
    }

    private OptionLeg leg(OptionType type, LocalDate expiration, double strike,
                          OpeningAction action, int quantity) {
        return new OptionLeg(type, expiration, BigDecimal.valueOf(strike), action, quantity);
    }
}
