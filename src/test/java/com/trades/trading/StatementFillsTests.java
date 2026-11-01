package com.trades.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StatementFillsTests {
    @Test
    void parsesOpeningAndClosingFlagsFromConfirmationText() {
        StatementFills.Flags flags = new StatementFills.Flags();
        StatementFills.parse("""
                2 S 10/30/26 11/02/26 3 1.4600000 438.00 0.00 0.06 0.38 V5342 437.56 TOS1102 5 1
                Desc: PUT  XSP    10/30/26   761 CBOE MINI S&P 500 INDEX UNSOLICITED CLOSING CONTRACT Interest/STTax: 0.00 CUSIP: 8CKNJB2
                Currency: USD ReportedPX: MarkUp/Down:
                2 B 10/30/26 11/02/26 7 3.4800000 2,436.00 0.00 0.14 0.59 V4572 2,436.73 TOS1102 5 1
                Desc: CALL IWM    11/09/26   280.5 ISHARES RUSSELL 2000 ETF UNSOLICITED OPEN CONTRACT Interest/STTax: 0.00 CUSIP: 9LSMHC8
                """, flags);

        assertThat(flags.size()).isEqualTo(2);
        assertThat(flags.take("2026-10-30", "XSP261030P00761000", false, new BigDecimal("3.0"))).isTrue();
        assertThat(flags.take("2026-10-30", "XSP261030P00761000", false, new BigDecimal("3"))).isNull();
        assertThat(flags.take("2026-10-30", "IWM261109C00280500", true, new BigDecimal("-7"))).isFalse();
    }
}
