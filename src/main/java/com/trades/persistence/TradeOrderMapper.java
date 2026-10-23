package com.trades.persistence;

import com.trades.trading.TradeOrderRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.trades.trading.TradeOrderEventRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TradeOrderMapper {
    @Insert("""
            INSERT INTO ivTrackedSymbols (symbol)
            VALUES (#{symbol})
            ON DUPLICATE KEY UPDATE symbol = VALUES(symbol)
            """)
    int trackSymbol(@Param("symbol") String symbol);

    @Select("SELECT pk FROM ivTrackedSymbols WHERE symbol = #{symbol}")
    Integer findSymbolPk(@Param("symbol") String symbol);

    @Insert("""
            INSERT INTO tradeOrders (ivTrackedSymbolsFk, spreadTypesFk, orderTypesFk, orderStatusesFk,
                                     tradeEnvironmentsFk, tradierOrderId, tradierPartnerId, limitPrice)
            VALUES (#{order.symbolPk},
                    (SELECT pk FROM spreadTypes WHERE code = #{order.spreadType}),
                    (SELECT pk FROM orderTypes WHERE code = #{order.orderType}),
                    (SELECT pk FROM orderStatuses WHERE code = 'OPEN'),
                    (SELECT pk FROM tradeEnvironments WHERE code = #{order.environment}),
                    #{order.tradierOrderId}, #{order.tradierPartnerId}, #{order.limitPrice})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "order.pk", keyColumn = "pk")
    int insertOrder(@Param("order") NewOrder order);

    @Insert("""
            INSERT INTO tradeOrderEvents (ivTrackedSymbolsFk, tradeOrdersFk, orderEventTypesFk, previewId,
                                          succeeded, endpoint, tradierOrderId, tradierPartnerId,
                                          requestJson, responseJson)
            VALUES (#{event.symbolPk}, #{event.orderPk},
                    (SELECT pk FROM orderEventTypes WHERE code = #{event.eventType}),
                    #{event.previewId}, #{event.succeeded}, #{event.endpoint}, #{event.tradierOrderId},
                    #{event.tradierPartnerId}, #{event.requestJson}, #{event.responseJson})
            """)
    int insertEvent(@Param("event") NewEvent event);

    @Update("""
            UPDATE tradeOrderEvents SET tradeOrdersFk = #{orderPk}
            WHERE previewId = #{previewId} AND tradeOrdersFk IS NULL
            """)
    int linkPreviewEvents(@Param("previewId") String previewId, @Param("orderPk") int orderPk);

    @Select("""
            SELECT e.pk, e.tradeOrdersFk AS orderPk, e.previewId, t.code AS eventType, e.succeeded, e.endpoint,
                   e.tradierOrderId, e.tradierPartnerId, e.requestJson, e.responseJson, e.sentAt
            FROM tradeOrderEvents e
            JOIN orderEventTypes t ON t.pk = e.orderEventTypesFk
            WHERE e.tradeOrdersFk = #{orderPk}
            ORDER BY e.sentAt, e.pk
            """)
    List<TradeOrderEventRecord> findEvents(@Param("orderPk") int orderPk);

    @Insert("""
            INSERT INTO tradeOrderLegs (tradeOrdersFk, legIndex, optionTypesFk, openingActionsFk,
                                        expiration, strike, quantity)
            VALUES (#{orderPk}, #{legIndex},
                    (SELECT pk FROM optionTypes WHERE code = #{optionType}),
                    (SELECT pk FROM openingActions WHERE code = #{action}),
                    #{expiration}, #{strike}, #{quantity})
            """)
    int insertLeg(@Param("orderPk") int orderPk,
                  @Param("legIndex") int legIndex,
                  @Param("optionType") String optionType,
                  @Param("expiration") LocalDate expiration,
                  @Param("strike") BigDecimal strike,
                  @Param("action") String action,
                  @Param("quantity") int quantity);

    @Select("""
            SELECT o.pk, s.symbol, o.tradierOrderId AS brokerOrderId, o.tradierPartnerId, env.code AS environment,
                   st.code AS spreadType, ot.code AS orderType, o.limitPrice, os.code AS status,
                   o.submittedAt, o.cancelledAt, NULL AS legs, NULL AS events
            FROM tradeOrders o
            JOIN ivTrackedSymbols s ON s.pk = o.ivTrackedSymbolsFk
            JOIN spreadTypes st ON st.pk = o.spreadTypesFk
            JOIN orderTypes ot ON ot.pk = o.orderTypesFk
            JOIN orderStatuses os ON os.pk = o.orderStatusesFk
            JOIN tradeEnvironments env ON env.pk = o.tradeEnvironmentsFk
            WHERE s.symbol = #{symbol}
            ORDER BY o.submittedAt DESC, o.pk DESC
            """)
    List<TradeOrderRecord> findOrdersBySymbol(@Param("symbol") String symbol);

    @Select("""
            SELECT o.pk, s.symbol, o.tradierOrderId AS brokerOrderId, o.tradierPartnerId, env.code AS environment,
                   st.code AS spreadType, ot.code AS orderType, o.limitPrice, os.code AS status,
                   o.submittedAt, o.cancelledAt, NULL AS legs, NULL AS events
            FROM tradeOrders o
            JOIN ivTrackedSymbols s ON s.pk = o.ivTrackedSymbolsFk
            JOIN spreadTypes st ON st.pk = o.spreadTypesFk
            JOIN orderTypes ot ON ot.pk = o.orderTypesFk
            JOIN orderStatuses os ON os.pk = o.orderStatusesFk
            JOIN tradeEnvironments env ON env.pk = o.tradeEnvironmentsFk
            WHERE o.submittedAt >= #{from} AND o.submittedAt < #{toExclusive}
            ORDER BY o.submittedAt DESC, o.pk DESC
            """)
    List<TradeOrderRecord> findOrdersBetween(@Param("from") LocalDateTime from,
                                             @Param("toExclusive") LocalDateTime toExclusive);

    @Select("""
            SELECT l.legIndex, ot.code AS optionType, l.expiration, l.strike, oa.code AS action, l.quantity
            FROM tradeOrderLegs l
            JOIN optionTypes ot ON ot.pk = l.optionTypesFk
            JOIN openingActions oa ON oa.pk = l.openingActionsFk
            WHERE l.tradeOrdersFk = #{orderPk}
            ORDER BY l.legIndex
            """)
    List<TradeOrderRecord.Leg> findLegs(@Param("orderPk") int orderPk);

    @Select("""
            SELECT o.pk AS orderPk, s.symbol, o.tradierOrderId, o.tradierStatus
            FROM tradeOrders o
            JOIN ivTrackedSymbols s ON s.pk = o.ivTrackedSymbolsFk
            JOIN orderStatuses os ON os.pk = o.orderStatusesFk
            WHERE os.code = 'OPEN' AND o.tradierOrderId IS NOT NULL
            ORDER BY o.pk
            """)
    List<TrackedOrder> findOpenOrders();

    @Update("""
            UPDATE tradeOrders
            SET orderStatusesFk = (SELECT pk FROM orderStatuses WHERE code = #{status}),
                tradierStatus = #{tradierStatus},
                statusCheckedAt = CURRENT_TIMESTAMP,
                filledAt = CASE WHEN #{status} = 'FILLED' THEN CURRENT_TIMESTAMP ELSE filledAt END,
                cancelledAt = CASE WHEN #{status} = 'CANCELLED' THEN CURRENT_TIMESTAMP ELSE cancelledAt END
            WHERE pk = #{orderPk}
            """)
    int updateStatus(@Param("orderPk") int orderPk, @Param("status") String status,
                     @Param("tradierStatus") String tradierStatus);

    @Select("SELECT pk FROM tradeOrders WHERE tradierOrderId = #{tradierOrderId}")
    Integer findOrderPkByTradierId(@Param("tradierOrderId") String tradierOrderId);

    @Select("SELECT tradierStatus FROM tradeOrders WHERE pk = #{orderPk}")
    String findTradierStatus(@Param("orderPk") int orderPk);

    @Select("SELECT COUNT(*) FROM tradeOrderEvents WHERE tradeOrdersFk = #{orderPk}")
    int countEvents(@Param("orderPk") int orderPk);

    @Insert("""
            INSERT INTO tradeOrders (ivTrackedSymbolsFk, spreadTypesFk, orderTypesFk, orderStatusesFk,
                                     tradeEnvironmentsFk, tradierOrderId, limitPrice, submittedAt,
                                     tradierStatus, filledAt, cancelledAt, statusCheckedAt)
            VALUES (#{order.symbolPk},
                    (SELECT pk FROM spreadTypes WHERE code = #{order.spreadType}),
                    (SELECT pk FROM orderTypes WHERE code = #{order.orderType}),
                    (SELECT pk FROM orderStatuses WHERE code = #{order.status}),
                    (SELECT pk FROM tradeEnvironments WHERE code = #{order.environment}),
                    #{order.tradierOrderId}, #{order.limitPrice}, #{order.submittedAt},
                    #{order.tradierStatus}, #{order.filledAt}, #{order.cancelledAt}, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "order.pk", keyColumn = "pk")
    int insertImportedOrder(@Param("order") ImportedOrder order);

    @Update("""
            UPDATE tradeOrders
            SET orderStatusesFk = (SELECT pk FROM orderStatuses WHERE code = #{order.status}),
                tradierStatus = #{order.tradierStatus},
                filledAt = COALESCE(#{order.filledAt}, filledAt),
                cancelledAt = COALESCE(#{order.cancelledAt}, cancelledAt),
                statusCheckedAt = CURRENT_TIMESTAMP
            WHERE pk = #{orderPk}
            """)
    int updateImportedOrder(@Param("orderPk") int orderPk, @Param("order") ImportedOrder order);

    @Insert("""
            INSERT INTO tradeOrderEvents (ivTrackedSymbolsFk, tradeOrdersFk, orderEventTypesFk, succeeded,
                                          endpoint, tradierOrderId, requestJson, responseJson, sentAt)
            VALUES (#{event.symbolPk}, #{event.orderPk},
                    (SELECT pk FROM orderEventTypes WHERE code = #{event.eventType}),
                    #{event.succeeded}, #{event.endpoint}, #{event.tradierOrderId},
                    #{event.requestJson}, #{event.responseJson}, #{event.sentAt})
            """)
    int insertEventAt(@Param("event") ImportedEvent event);

    class ImportedOrder {
        public int pk;
        public int symbolPk;
        public String tradierOrderId;
        public String environment;
        public String spreadType;
        public String orderType;
        public String status;
        public String tradierStatus;
        public BigDecimal limitPrice;
        public LocalDateTime submittedAt;
        public LocalDateTime filledAt;
        public LocalDateTime cancelledAt;
    }

    class ImportedEvent {
        public int symbolPk;
        public int orderPk;
        public String eventType;
        public boolean succeeded = true;
        public String endpoint;
        public String tradierOrderId;
        public String requestJson;
        public String responseJson;
        public LocalDateTime sentAt;
    }

    record TrackedOrder(int orderPk, String symbol, String tradierOrderId, String tradierStatus) {
    }

    class NewEvent {
        public int symbolPk;
        public Integer orderPk;
        public String previewId;
        public String eventType;
        public boolean succeeded;
        public String endpoint;
        public String tradierOrderId;
        public String tradierPartnerId;
        public String requestJson;
        public String responseJson;
    }

    class NewOrder {
        public int pk;
        public int symbolPk;
        public String tradierOrderId;
        public String tradierPartnerId;
        public String environment;
        public String spreadType;
        public String orderType;
        public BigDecimal limitPrice;
    }
}
