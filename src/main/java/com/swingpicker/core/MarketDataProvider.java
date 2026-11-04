package com.swingpicker.core;

import com.swingpicker.domain.Bar;

import java.util.List;
import java.util.Optional;

/** Daily bars for an NSE symbol (e.g. "RELIANCE", or an index like "^NSEI"), oldest first. */
public interface MarketDataProvider {

    /** Completed sessions only. */
    List<Bar> dailyBars(String symbol, int years);

    /** Today's still-forming bar while the market is open; empty otherwise (or if the source doesn't offer one). */
    default Optional<Bar> liveBar(String symbol) {
        return Optional.empty();
    }
}
