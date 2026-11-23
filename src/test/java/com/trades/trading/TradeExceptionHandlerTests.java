package com.trades.trading;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import static org.assertj.core.api.Assertions.assertThat;

class TradeExceptionHandlerTests {
    @Test
    void reportsTradierHttpStatusAndCredentialGuidanceWithoutResponseBody() {
        var exception = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                HttpHeaders.EMPTY, "sensitive response".getBytes(), null);

        var problem = new TradeExceptionHandler().brokerUnavailable(exception);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problem.getDetail()).contains("HTTP 401", "Check the credentials")
                .doesNotContain("sensitive response");
    }
}
