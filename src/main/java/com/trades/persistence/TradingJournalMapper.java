package com.trades.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface TradingJournalMapper {
    @Insert("""
            INSERT INTO ivTrackedSymbols (symbol)
            VALUES (#{symbol})
            ON DUPLICATE KEY UPDATE symbol = VALUES(symbol)
            """)
    int trackSymbol(@Param("symbol") String symbol);

    @Select("SELECT pk FROM ivTrackedSymbols WHERE symbol = #{symbol}")
    Integer findSymbolPk(@Param("symbol") String symbol);

    @Insert("""
            INSERT INTO tradingJournalLegs (
                tradierOrderId, tradierLegId, legIndex, ivTrackedSymbolsFk, spreadTypesFk, optionTypesFk,
                openingActionsFk, tradeEnvironmentsFk, orderClass, orderKind, orderStatus, orderDuration,
                orderQuantity, orderPrice, orderAvgFillPrice, orderExecQuantity, orderCreatedAt,
                orderTransactionAt, side, optionSymbol, expiration, strike, legStatus, legQuantity,
                legAvgFillPrice, legExecQuantity, legRemainingQuantity, legJson)
            VALUES (
                #{leg.tradierOrderId}, #{leg.tradierLegId}, #{leg.legIndex}, #{leg.symbolPk},
                (SELECT pk FROM spreadTypes WHERE code = #{leg.spreadType}),
                (SELECT pk FROM optionTypes WHERE code = #{leg.optionType}),
                (SELECT pk FROM openingActions WHERE code = #{leg.action}),
                (SELECT pk FROM tradeEnvironments WHERE code = #{leg.environment}),
                #{leg.orderClass}, #{leg.orderKind}, #{leg.orderStatus}, #{leg.orderDuration},
                #{leg.orderQuantity}, #{leg.orderPrice}, #{leg.orderAvgFillPrice}, #{leg.orderExecQuantity},
                #{leg.orderCreatedAt}, #{leg.orderTransactionAt}, #{leg.side}, #{leg.optionSymbol},
                #{leg.expiration}, #{leg.strike}, #{leg.legStatus}, #{leg.legQuantity},
                #{leg.legAvgFillPrice}, #{leg.legExecQuantity}, #{leg.legRemainingQuantity}, #{leg.legJson})
            ON DUPLICATE KEY UPDATE
                tradierLegId = VALUES(tradierLegId),
                ivTrackedSymbolsFk = VALUES(ivTrackedSymbolsFk),
                spreadTypesFk = VALUES(spreadTypesFk),
                optionTypesFk = VALUES(optionTypesFk),
                openingActionsFk = VALUES(openingActionsFk),
                orderClass = VALUES(orderClass),
                orderKind = VALUES(orderKind),
                orderStatus = VALUES(orderStatus),
                orderDuration = VALUES(orderDuration),
                orderQuantity = VALUES(orderQuantity),
                orderPrice = VALUES(orderPrice),
                orderAvgFillPrice = VALUES(orderAvgFillPrice),
                orderExecQuantity = VALUES(orderExecQuantity),
                orderCreatedAt = VALUES(orderCreatedAt),
                orderTransactionAt = VALUES(orderTransactionAt),
                side = VALUES(side),
                optionSymbol = VALUES(optionSymbol),
                expiration = VALUES(expiration),
                strike = VALUES(strike),
                legStatus = VALUES(legStatus),
                legQuantity = VALUES(legQuantity),
                legAvgFillPrice = VALUES(legAvgFillPrice),
                legExecQuantity = VALUES(legExecQuantity),
                legRemainingQuantity = VALUES(legRemainingQuantity),
                legJson = VALUES(legJson),
                importedAt = CURRENT_TIMESTAMP
            """)
    int upsertLeg(@Param("leg") ImportedLeg leg);

    record ImportedLeg(
            String tradierOrderId, String tradierLegId, int legIndex, int symbolPk, String spreadType,
            String optionType, String action, String environment, String orderClass, String orderKind,
            String orderStatus, String orderDuration, BigDecimal orderQuantity, BigDecimal orderPrice,
            BigDecimal orderAvgFillPrice, BigDecimal orderExecQuantity, LocalDateTime orderCreatedAt,
            LocalDateTime orderTransactionAt, String side, String optionSymbol, LocalDate expiration,
            BigDecimal strike, String legStatus, BigDecimal legQuantity, BigDecimal legAvgFillPrice,
            BigDecimal legExecQuantity, BigDecimal legRemainingQuantity, String legJson) {
    }
}
