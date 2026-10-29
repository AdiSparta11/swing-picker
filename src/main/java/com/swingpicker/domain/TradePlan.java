package com.swingpicker.domain;

import java.time.LocalDate;

/**
 * What a setup proposes for one stock: where to get in, where you're wrong, where you take profit,
 * and the latest day you hold regardless.
 */
public record TradePlan(
        String symbol,
        String setupName,
        LocalDate signalDate,
        double entry,
        double stop,
        double target,
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
