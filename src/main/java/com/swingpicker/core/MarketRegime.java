package com.swingpicker.core;

import com.swingpicker.domain.Bar;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Is the overall market in an uptrend on a given day? "Up" means the Nifty 50 closed above its
 * 200-day average AND that average is higher than 20 sessions earlier. Both conditions use only data up to that day.
 *
 * Index bars come through the cached data provider; the day-by-day answer is recomputed only
 * when the provider hands back a new list.
 */
public class MarketRegime {

    public static final String NIFTY = "^NSEI";

    private final MarketDataProvider data;
    private final int historyYears;
    private List<Bar> source;
    private Map<LocalDate, Boolean> upByDate = Map.of();
    private LocalDate latest;

    public MarketRegime(MarketDataProvider data, int historyYears) {
        this.data = data;
        this.historyYears = historyYears;
    }

    /** Empty when the index data can't be loaded or doesn't cover that day. */
    public synchronized Optional<Boolean> isUp(LocalDate date) {
        refresh();
        return Optional.ofNullable(upByDate.get(date));
    }

    /** The regime on the latest index session, with that date. */
    public synchronized Optional<Map.Entry<LocalDate, Boolean>> latest() {
        refresh();
        return latest == null ? Optional.empty() : Optional.of(Map.entry(latest, upByDate.get(latest)));
    }

    private void refresh() {
        List<Bar> bars;
        try {
            bars = data.dailyBars(NIFTY, historyYears);
        } catch (RuntimeException e) {
            if (e instanceof UnsupportedOperationException) {
                throw e;
            }
            bars = List.of();
        }
        if (bars == source) {
            return;
        }
        source = bars;
        upByDate = compute(bars);
        latest = bars.isEmpty() ? null : bars.get(bars.size() - 1).date();
        if (latest != null && !upByDate.containsKey(latest)) {
            latest = null;
        }
    }

    /** Day-by-day regime for every bar with enough history (220 sessions). */
    public static Map<LocalDate, Boolean> compute(List<Bar> bars) {
        Map<LocalDate, Boolean> out = new HashMap<>();
        double[] prefix = new double[bars.size() + 1];
        for (int k = 0; k < bars.size(); k++) {
            prefix[k + 1] = prefix[k] + bars.get(k).close();
        }
        for (int i = 219; i < bars.size(); i++) {
            double sma200 = (prefix[i + 1] - prefix[i - 199]) / 200;
            double sma200Before = (prefix[i - 19] - prefix[i - 219]) / 200;
            out.put(bars.get(i).date(), bars.get(i).close() > sma200 && sma200 > sma200Before);
        }
        return out;
    }
}
