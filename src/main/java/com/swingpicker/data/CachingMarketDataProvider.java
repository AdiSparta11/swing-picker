package com.swingpicker.data;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.domain.Bar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily bars only change once a day, so one fetch per symbol per TTL is plenty.
 * In-memory on purpose: Render's free instance loses its disk anyway, and a restart just means one slow first click.
 * Failures are not cached, so a transient Yahoo error retries on the next request.
 */
public class CachingMarketDataProvider implements MarketDataProvider {

    private record Entry(List<Bar> bars, Instant fetchedAt) {
    }

    private final MarketDataProvider delegate;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CachingMarketDataProvider(MarketDataProvider delegate, Duration ttl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public List<Bar> dailyBars(String symbol, int years) {
        String key = symbol.trim().toUpperCase() + "|" + years;
        Instant now = clock.instant();
        Entry hit = cache.get(key);
        if (hit != null && hit.fetchedAt().plus(ttl).isAfter(now)) {
            return hit.bars();
        }
        List<Bar> bars = List.copyOf(delegate.dailyBars(symbol, years));
        cache.put(key, new Entry(bars, now));
        return bars;
    }
}
