package com.trades.trading;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrdersCsvTests {
    private static final String CSV = """
            "ID","Parent ID","Symbol","Side","QTY","Type","Dur","Filled/Qty","Status","Class","Price","Remaining Qty","Reason","Last Fill Price","Last Fill Qty","Create Date","Transaction Date","Expiration Date","Strike","Option Type","Tag"
            11,10,"BITO","sell_to_open",1,"credit","day",,"filled","option",0.5,0,,0.7,1,"2026-10-30T13:45:16.408Z","2026-10-30T13:45:16.551Z","2026-11-02",9.5,"Put",
            12,10,"BITO","buy_to_open",1,"credit","day",,"filled","option",0.5,0,,0.2,1,"2026-10-30T13:45:16.408Z","2026-10-30T13:45:16.551Z","2026-11-02",9,"Put",
            20,,"USMV","buy",1,"market","day",,"filled","equity",,0,,90,1,"2026-10-02T15:28:16.103Z","2026-10-02T15:28:16.200Z",,,,
            """;

    @Test
    void groupsRowsByParentIdIntoMultilegOrders() {
        List<ObjectNode> orders = new OrdersCsv("none").parse(CSV);

        assertThat(orders).hasSize(2);
        ObjectNode spread = orders.get(0);
        assertThat(spread.path("id").asText()).isEqualTo("10");
        assertThat(spread.path("class").asText()).isEqualTo("multileg");
        assertThat(spread.path("leg")).hasSize(2);
        assertThat(spread.path("leg").get(0).path("option_symbol").asText()).isEqualTo("BITO261102P00009500");
        assertThat(spread.path("leg").get(1).path("option_symbol").asText()).isEqualTo("BITO261102P00009000");
        assertThat(orders.get(1).path("class").asText()).isEqualTo("equity");

        Map<String, Integer> keys = OrdersCsv.filledLegKeys(orders);
        assertThat(keys).containsEntry("2026-10-30|BITO261102P00009500|S|1", 1);
    }
}
