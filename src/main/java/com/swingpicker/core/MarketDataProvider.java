package com.swingpicker.core;

import com.swingpicker.domain.Bar;

import java.util.List;

/** Step 8: daily bars for an NSE symbol (e.g. "RELIANCE"), oldest first. Yahoo ".NS" first, swappable later. */
public interface MarketDataProvider {
    List<Bar> dailyBars(String symbol, int years);
}
