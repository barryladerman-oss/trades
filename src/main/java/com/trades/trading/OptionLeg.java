package com.trades.trading;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OptionLeg(
        @NotNull OptionType optionType,
        @NotNull LocalDate expiration,
        @NotNull @DecimalMin("0.001") @Positive BigDecimal strike,
        @NotNull OpeningAction action,
        @Min(1) @Max(1000) int quantity
) {
}
