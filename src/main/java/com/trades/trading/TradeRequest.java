package com.trades.trading;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record TradeRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9.-]{0,9}") String symbol,
        @NotNull SpreadType spreadType,
        @NotEmpty @Size(max = 4) List<@NotNull @Valid OptionLeg> legs,
        @NotNull OrderType orderType,
        @NotNull @DecimalMin("0.00") BigDecimal limitPrice
) {
}
