package com.trades.trading;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Component
public class TradeValidator {
    public void validate(@Nullable TradeRequest trade) {
        if (trade == null) {
            throw new IllegalArgumentException("Trade is required.");
        }
        require(!trade.legs().isEmpty(), "At least one option leg is required.");
        require(trade.legs().size() <= 4, "A trade cannot have more than four option legs.");
        if (trade.spreadType() == SpreadType.SINGLE_LEG) {
            require(trade.legs().size() == 1, "A single-leg trade requires exactly one leg.");
        }
        if (trade.limitPrice().scale() > 2) {
            throw new IllegalArgumentException("Limit price must have no more than two decimal places.");
        }
        if (trade.orderType() == OrderType.EVEN && trade.limitPrice().signum() != 0) {
            throw new IllegalArgumentException("An even order must have a limit price of 0.00.");
        }
        if (trade.orderType() != OrderType.EVEN && trade.limitPrice().signum() <= 0) {
            throw new IllegalArgumentException("Debit and credit orders require a limit price greater than zero.");
        }
        if (trade.spreadType() == SpreadType.SINGLE_LEG) {
            OpeningAction action = trade.legs().get(0).action();
            OrderType expected = action == OpeningAction.BUY_TO_OPEN ? OrderType.DEBIT : OrderType.CREDIT;
            require(trade.orderType() == expected,
                    "A single-leg buy-to-open order must be a debit; a sell-to-open order must be a credit.");
        }
        if (trade.legs().stream().anyMatch(leg -> leg.expiration().isBefore(LocalDate.now()))) {
            throw new IllegalArgumentException("Option expirations cannot be in the past.");
        }

        List<OptionLeg> legs = trade.legs();
        require(legs.stream().allMatch(leg -> leg != null
                        && leg.quantity() >= 1 && leg.quantity() <= 1000),
                "Every option leg quantity must be between 1 and 1000 contracts.");
        switch (trade.spreadType()) {
            case SINGLE_LEG -> require(legs.size() == 1, "A single-leg trade requires exactly one leg.");
            case VERTICAL -> validateVertical(legs);
            case IRON_CONDOR -> validateIronCondor(legs, false);
            case IRON_BUTTERFLY -> validateIronCondor(legs, true);
            case BUTTERFLY -> validateButterfly(legs);
            case CALENDAR -> validateCalendar(legs);
            case DIAGONAL -> validateDiagonal(legs);
            case DOUBLE_DIAGONAL -> validateDoubleDiagonal(legs);
        }
    }

    private void validateVertical(List<OptionLeg> legs) {
        require(legs.size() == 2, "A vertical spread requires exactly two legs.");
        OptionLeg first = legs.get(0);
        OptionLeg second = legs.get(1);
        require(first.optionType() == second.optionType(), "Vertical legs must use the same option type.");
        require(first.expiration().equals(second.expiration()), "Vertical legs must have the same expiration.");
        require(first.strike().compareTo(second.strike()) != 0, "Vertical legs must have different strikes.");
        require(oppositeActions(first, second), "Vertical legs must have opposite opening actions.");
    }

    private void validateIronCondor(List<OptionLeg> legs, boolean butterfly) {
        require(legs.size() == 4, "This spread requires exactly four legs.");
        List<OptionLeg> puts = byType(legs, OptionType.PUT);
        List<OptionLeg> calls = byType(legs, OptionType.CALL);
        require(puts.size() == 2 && calls.size() == 2, "The spread requires two put legs and two call legs.");
        require(sameExpiration(legs), "All legs must have the same expiration.");

        OptionLeg putLow = puts.stream().min(Comparator.comparing(OptionLeg::strike)).orElseThrow();
        OptionLeg putHigh = puts.stream().max(Comparator.comparing(OptionLeg::strike)).orElseThrow();
        OptionLeg callLow = calls.stream().min(Comparator.comparing(OptionLeg::strike)).orElseThrow();
        OptionLeg callHigh = calls.stream().max(Comparator.comparing(OptionLeg::strike)).orElseThrow();
        if (butterfly) {
            require(putHigh.strike().compareTo(callLow.strike()) == 0,
                    "Iron butterfly short put and short call must share the middle strike.");
            require(putLow.strike().compareTo(putHigh.strike()) < 0
                            && callHigh.strike().compareTo(callLow.strike()) > 0,
                    "Iron butterfly long wings must be outside the shared middle strike.");
            require(putLow.action() == OpeningAction.BUY_TO_OPEN
                            && putHigh.action() == OpeningAction.SELL_TO_OPEN
                            && callLow.action() == OpeningAction.SELL_TO_OPEN
                            && callHigh.action() == OpeningAction.BUY_TO_OPEN,
                            "An iron butterfly requires long outer wings and short put and call legs at the shared center.");
        } else {
            require(putHigh.strike().compareTo(callLow.strike()) < 0,
                    "Iron condor short put strike must be below its short call strike.");
            require(putLow.action() == OpeningAction.BUY_TO_OPEN
                            && putHigh.action() == OpeningAction.SELL_TO_OPEN
                            && callLow.action() == OpeningAction.SELL_TO_OPEN
                            && callHigh.action() == OpeningAction.BUY_TO_OPEN,
                    "An iron condor requires short inner strikes and long outer wings.");
        }
        if (!butterfly) {
            require(putLow.action() == callHigh.action() && putHigh.action() == callLow.action()
                            && oppositeActions(putLow, putHigh),
                    "Both wings must have one opening action and both inner legs the opposite action.");
        }
    }

    private void validateButterfly(List<OptionLeg> legs) {
        require(legs.size() == 3, "A butterfly requires exactly three legs.");
        require(legs.stream().map(OptionLeg::optionType).distinct().count() == 1,
                "Butterfly legs must use the same option type.");
        require(sameExpiration(legs), "Butterfly legs must have the same expiration.");
        List<OptionLeg> ordered = legs.stream().sorted(Comparator.comparing(OptionLeg::strike)).toList();
        require(ordered.get(0).strike().compareTo(ordered.get(1).strike()) < 0
                        && ordered.get(1).strike().compareTo(ordered.get(2).strike()) < 0,
                "Butterfly strikes must be strictly increasing.");
        require(ordered.get(1).strike().subtract(ordered.get(0).strike())
                        .compareTo(ordered.get(2).strike().subtract(ordered.get(1).strike())) == 0,
                "Butterfly middle strike must be centered between the outer strikes.");
        require(ordered.get(0).action() == ordered.get(2).action()
                        && oppositeActions(ordered.get(0), ordered.get(1)),
                "Butterfly outer legs must share an action opposite to the middle leg.");
    }

    private void validateCalendar(List<OptionLeg> legs) {
        require(legs.size() == 2, "A calendar spread requires exactly two legs.");
        OptionLeg first = legs.get(0);
        OptionLeg second = legs.get(1);
        require(first.optionType() == second.optionType(), "Calendar legs must use the same option type.");
        require(first.strike().compareTo(second.strike()) == 0, "Calendar legs must have the same strike.");
        require(!first.expiration().equals(second.expiration()), "Calendar legs must have different expirations.");
        require(oppositeActions(first, second), "Calendar legs must have opposite opening actions.");
        OptionLeg near = first.expiration().isBefore(second.expiration()) ? first : second;
        require(near.action() == OpeningAction.SELL_TO_OPEN,
                "A calendar spread must sell the nearer expiration and buy the farther expiration.");
    }

    private void validateDiagonal(List<OptionLeg> legs) {
        require(legs.size() == 2, "A diagonal spread requires exactly two legs.");
        OptionLeg first = legs.get(0);
        OptionLeg second = legs.get(1);
        require(first.optionType() == second.optionType(), "Diagonal legs must use the same option type.");
        require(first.strike().compareTo(second.strike()) != 0, "Diagonal legs must have different strikes.");
        require(!first.expiration().equals(second.expiration()), "Diagonal legs must have different expirations.");
        require(oppositeActions(first, second), "Diagonal legs must have opposite opening actions.");
        OptionLeg shortLeg = first.action() == OpeningAction.SELL_TO_OPEN ? first : second;
        OptionLeg longLeg = shortLeg == first ? second : first;
        require(shortLeg.expiration().isBefore(longLeg.expiration()),
                "A diagonal must sell the nearer expiration and buy the farther expiration.");
        require(shortLeg.optionType() == OptionType.CALL
                        ? longLeg.strike().compareTo(shortLeg.strike()) > 0
                        : longLeg.strike().compareTo(shortLeg.strike()) < 0,
                "A diagonal long strike must be farther out of the money than its short strike.");
    }

    private void validateDoubleDiagonal(List<OptionLeg> legs) {
        require(legs.size() == 4, "A double diagonal requires exactly four legs.");
        List<OptionLeg> puts = byType(legs, OptionType.PUT);
        List<OptionLeg> calls = byType(legs, OptionType.CALL);
        require(puts.size() == 2 && calls.size() == 2, "A double diagonal requires two puts and two calls.");
        require(sameExpirationForAction(legs, OpeningAction.SELL_TO_OPEN)
                        && sameExpirationForAction(legs, OpeningAction.BUY_TO_OPEN),
                "All short legs must share one expiration and all long legs must share one expiration.");
        validateDiagonalPair(puts, "put");
        validateDiagonalPair(calls, "call");
        OptionLeg shortPut = puts.stream().filter(leg -> leg.action() == OpeningAction.SELL_TO_OPEN)
                .findFirst().orElseThrow();
        OptionLeg longPut = puts.stream().filter(leg -> leg.action() == OpeningAction.BUY_TO_OPEN)
                .findFirst().orElseThrow();
        OptionLeg shortCall = calls.stream().filter(leg -> leg.action() == OpeningAction.SELL_TO_OPEN)
                .findFirst().orElseThrow();
        OptionLeg longCall = calls.stream().filter(leg -> leg.action() == OpeningAction.BUY_TO_OPEN)
                .findFirst().orElseThrow();
        require(longPut.strike().compareTo(shortPut.strike()) < 0
                        && shortPut.strike().compareTo(shortCall.strike()) <= 0
                        && shortCall.strike().compareTo(longCall.strike()) < 0,
                "A double diagonal requires long put, short put, short call (same strike allowed), then long call strikes.");
    }

    private void validateDiagonalPair(List<OptionLeg> pair, String type) {
        OptionLeg first = pair.get(0);
        OptionLeg second = pair.get(1);
        require(first.strike().compareTo(second.strike()) != 0
                        && !first.expiration().equals(second.expiration()),
                "The " + type + " legs must differ in strike and expiration.");
        require(oppositeActions(first, second),
                "The " + type + " legs must have opposite opening actions.");
        OptionLeg near = first.expiration().isBefore(second.expiration()) ? first : second;
        require(near.action() == OpeningAction.SELL_TO_OPEN,
                "The nearer-expiration " + type + " leg must be sold to open.");
    }

    private List<OptionLeg> byType(List<OptionLeg> legs, OptionType type) {
        return legs.stream().filter(leg -> leg.optionType() == type).toList();
    }

    private boolean sameExpiration(List<OptionLeg> legs) {
        return legs.stream().map(OptionLeg::expiration).distinct().count() == 1;
    }

    private boolean sameExpirationForAction(List<OptionLeg> legs, OpeningAction action) {
        return legs.stream()
                .filter(leg -> leg.action() == action)
                .map(OptionLeg::expiration)
                .distinct()
                .count() == 1;
    }

    private boolean oppositeActions(OptionLeg first, OptionLeg second) {
        return first.action() != second.action();
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
