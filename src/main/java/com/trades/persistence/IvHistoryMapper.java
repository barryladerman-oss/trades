package com.trades.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface IvHistoryMapper {
    @Insert("""
            INSERT INTO ivTrackedSymbols (symbol)
            VALUES (#{symbol})
            ON DUPLICATE KEY UPDATE symbol = VALUES(symbol)
            """)
    int trackSymbol(@Param("symbol") String symbol);

    @Select("""
            SELECT sample.impliedVolatility
            FROM ivDailySamples sample
            JOIN ivTrackedSymbols tracked ON tracked.pk = sample.ivTrackedSymbolsFk
            WHERE tracked.symbol = #{symbol} AND sample.observationDate = #{observationDate}
            """)
    BigDecimal findSample(@Param("symbol") String symbol,
                          @Param("observationDate") LocalDate observationDate);

    @Insert("""
            INSERT INTO ivDailySamples (ivTrackedSymbolsFk, observationDate, impliedVolatility)
            SELECT pk, #{observationDate}, #{impliedVolatility}
            FROM ivTrackedSymbols
            WHERE symbol = #{symbol}
            ON DUPLICATE KEY UPDATE impliedVolatility = VALUES(impliedVolatility)
            """)
    int saveSample(@Param("symbol") String symbol,
                   @Param("observationDate") LocalDate observationDate,
                   @Param("impliedVolatility") BigDecimal impliedVolatility);

    @Select("""
            SELECT sample.impliedVolatility
            FROM ivDailySamples sample
            JOIN ivTrackedSymbols tracked ON tracked.pk = sample.ivTrackedSymbolsFk
            WHERE tracked.symbol = #{symbol}
              AND sample.observationDate >= #{startDate}
              AND sample.observationDate < #{endDate}
            ORDER BY sample.observationDate DESC
            LIMIT #{limit}
            """)
    List<BigDecimal> findHistoricalSamples(@Param("symbol") String symbol,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate,
                                           @Param("limit") int limit);

    @Select("""
            SELECT symbol
            FROM ivTrackedSymbols
            ORDER BY symbol
            """)
    List<String> findTrackedSymbols();

    @Delete("""
            DELETE FROM ivTrackedSymbols
            WHERE symbol = #{symbol}
            """)
    int deleteTrackedSymbol(@Param("symbol") String symbol);
}
