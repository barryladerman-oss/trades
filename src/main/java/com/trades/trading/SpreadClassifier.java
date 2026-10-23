package com.trades.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifies a spread type from the properties of an order's option legs. */
public final class SpreadClassifier {
    private static final Pattern OCC = Pattern.compile("^(.{1,6}?)(\\d{6})([CP])(\\d{8})$");
    private static final DateTimeFormatter OCC_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private SpreadClassifier() {
    }

    public record Leg(String optionType, LocalDate expiration, BigDecimal strike, BigDecimal quantity) {
        /** Parses an OCC option symbol such as SPY261127C00761000; returns null for non-options. */
        public static Leg parse(String optionSymbol, BigDecimal quantity) {
            if (optionSymbol == null) {
                return null;
            }
            Matcher matcher = OCC.matcher(optionSymbol.trim());
            if (!matcher.matches()) {
                return null;
            }
            return new Leg("C".equals(matcher.group(3)) ? "CALL" : "PUT",
                    LocalDate.parse(matcher.group(2), OCC_DATE),
                    new BigDecimal(matcher.group(4)).movePointLeft(3).setScale(3, RoundingMode.UNNECESSARY),
                    quantity);
        }
    }

    /** Legs may contain null entries for non-option (equity) legs. */
    public static String classify(List<Leg> legs) {
        if (legs.isEmpty() || legs.stream().anyMatch(leg -> leg == null)) {
            return legs.size() == 1 ? "SINGLE_LEG" : "CUSTOM";
        }
        return switch (legs.size()) {
            case 1 -> "SINGLE_LEG";
            case 2 -> twoLegs(legs.get(0), legs.get(1));
            case 3 -> threeLegs(legs);
            case 4 -> fourLegs(legs);
            default -> "CUSTOM";
        };
    }

    private static String twoLegs(Leg a, Leg b) {
        boolean sameExpiration = a.expiration().equals(b.expiration());
        boolean sameStrike = a.strike().compareTo(b.strike()) == 0;
        if (a.optionType().equals(b.optionType())) {
            if (sameExpiration && !sameStrike) {
                return "VERTICAL";
            }
            if (!sameExpiration && sameStrike) {
                return "CALENDAR";
            }
            return sameExpiration ? "CUSTOM" : "DIAGONAL";
        }
        if (!sameExpiration) {
            return "CUSTOM";
        }
        return sameStrike ? "STRADDLE" : "STRANGLE";
    }

    private static String threeLegs(List<Leg> legs) {
        List<Leg> sorted = legs.stream().sorted((x, y) -> x.strike().compareTo(y.strike())).toList();
        Leg low = sorted.get(0);
        Leg mid = sorted.get(1);
        Leg high = sorted.get(2);
        boolean sameKind = legs.stream().allMatch(leg -> leg.optionType().equals(low.optionType())
                && leg.expiration().equals(low.expiration()));
        boolean distinct = low.strike().compareTo(mid.strike()) < 0 && mid.strike().compareTo(high.strike()) < 0;
        boolean ratio = low.quantity() != null && mid.quantity() != null && high.quantity() != null
                && low.quantity().compareTo(high.quantity()) == 0
                && mid.quantity().compareTo(low.quantity().multiply(BigDecimal.valueOf(2))) == 0;
        return sameKind && distinct && ratio ? "BUTTERFLY" : "CUSTOM";
    }

    private static String fourLegs(List<Leg> legs) {
        List<Leg> calls = legs.stream().filter(leg -> "CALL".equals(leg.optionType()))
                .sorted((x, y) -> x.strike().compareTo(y.strike())).toList();
        List<Leg> puts = legs.stream().filter(leg -> "PUT".equals(leg.optionType()))
                .sorted((x, y) -> x.strike().compareTo(y.strike())).toList();
        if (calls.size() != 2 || puts.size() != 2) {
            return "CUSTOM";
        }
        long expirations = legs.stream().map(Leg::expiration).distinct().count();
        if (expirations == 1) {
            if (puts.get(1).strike().compareTo(calls.get(0).strike()) == 0
                    && puts.get(0).strike().compareTo(puts.get(1).strike()) < 0
                    && calls.get(0).strike().compareTo(calls.get(1).strike()) < 0) {
                return "IRON_BUTTERFLY";
            }
            boolean ordered = puts.get(0).strike().compareTo(puts.get(1).strike()) < 0
                    && puts.get(1).strike().compareTo(calls.get(0).strike()) < 0
                    && calls.get(0).strike().compareTo(calls.get(1).strike()) < 0;
            return ordered ? "IRON_CONDOR" : "CUSTOM";
        }
        boolean eachSideTimeSpread = puts.get(0).expiration().compareTo(puts.get(1).expiration()) != 0
                && calls.get(0).expiration().compareTo(calls.get(1).expiration()) != 0
                && expirations == 2;
        boolean strikesDiffer = puts.get(0).strike().compareTo(puts.get(1).strike()) != 0
                && calls.get(0).strike().compareTo(calls.get(1).strike()) != 0;
        return eachSideTimeSpread && strikesDiffer ? "DOUBLE_DIAGONAL" : "CUSTOM";
    }
}
