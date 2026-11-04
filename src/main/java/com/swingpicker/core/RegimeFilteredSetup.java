package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;

import java.util.List;
import java.util.Optional;

/**
 * Wraps a setup so it only fires when the Nifty 50 is in an uptrend on the signal day (see {@link MarketRegime}).
 * Same entry, stop and target as the inner setup. If index data is missing for that day, it doesn't fire.
 * The regime check runs first because it's cheap, which also speeds up the backtest.
 */
public class RegimeFilteredSetup implements Setup {

    private final Setup inner;
    private final MarketRegime regime;

    public RegimeFilteredSetup(Setup inner, MarketRegime regime) {
        this.inner = inner;
        this.regime = regime;
    }

    @Override
    public String name() {
        return inner.name() + " + nifty-uptrend";
    }

    @Override
    public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
        if (bars.isEmpty() || !regime.isUp(bars.get(bars.size() - 1).date()).orElse(false)) {
            return Optional.empty();
        }
        return inner.detect(symbol, bars).map(p -> new TradePlan(p.symbol(), name(), p.signalDate(), p.entry(),
                p.stop(), p.target(), p.maxEntry(), p.maxHoldDays()));
    }
}
