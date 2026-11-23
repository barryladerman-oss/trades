package com.trades.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionControllerTests {
    @Test
    void includesEquitiesAlongsideOptionPositions() throws Exception {
        TradierGateway gateway = mock(TradierGateway.class);
        when(gateway.accountPositions("SANDBOX")).thenReturn(new ObjectMapper().readTree("""
                {"positions":{"position":[
                  {"symbol":"AAPL","quantity":20,"cost_basis":3500},
                  {"symbol":"MSFT","quantity":5,"cost_basis":2000},
                  {"symbol":"SPY261218C00600000","quantity":1,"cost_basis":100},
                  {"symbol":"CASH","quantity":0,"cost_basis":0}
                ]}}
                """));
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        PositionController controller = new PositionController(gateway, mock(OrderStatusPoller.class),
                mock(TradeOrderService.class), jdbcTemplate,
                new TradierEnvironment(new TradierProperties("ACCOUNT", "token")));

        List<PositionController.PositionGroup> positions = controller.positions();

        assertThat(positions).extracting(PositionController.PositionGroup::symbol)
                .containsExactly("AAPL", "MSFT", "SPY");
        assertThat(positions.subList(0, 2)).allSatisfy(position -> {
            assertThat(position.spreadType()).isEqualTo("EQUITY");
            assertThat(position.legs()).singleElement().satisfies(leg -> {
                assertThat(leg.optionType()).isEqualTo("EQUITY");
                assertThat(leg.expiration()).isNull();
                assertThat(leg.strike()).isNull();
            });
        });
        assertThat(positions.get(2).spreadType()).isEqualTo("SINGLE_LEG");
    }
}
