package com.swingpicker.domain;

import java.time.LocalDate;

/**
 * What a setup proposes for one stock, planned from the signal day's close.
 *
 * @param entry       reference entry: the signal day's close. You buy at the next session's open.
 * @param stop        exit if price trades here: the plan was wrong
 * @param target      exit here: the plan worked
 * @param maxEntry    don't buy above this. A gap-up past it makes the risk too big for the target
 * @param maxHoldDays exit at the close after this many sessions if neither stop nor target hit
 */
public record TradePlan(
        String symbol,
        String setupName,
        LocalDate signalDate,
        double entry,
        double stop,
        double target,
        double maxEntry,
        int maxHoldDays) {

    /** Rupees lost per share if the stop fills exactly at its price. This is "1R" for the trade. */
    public double riskPerShare() {
        return entry - stop;
    }

    /** Target expressed in R: how many units of risk the target pays. */
    public double targetInR() {
        return (target - entry) / riskPerShare();
    }
}
