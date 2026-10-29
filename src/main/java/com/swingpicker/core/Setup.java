package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;

import java.util.List;
import java.util.Optional;

/**
 * Steps 1-2: a swing setup. Looks ONLY at bars up to and including the last one
 * (no peeking ahead, or the backtest lies) and, if the setup fired on that last bar,
 * proposes entry, stop, target and max hold.
 */
public interface Setup {
    String name();

    Optional<TradePlan> detect(String symbol, List<Bar> barsUpToToday);
}
