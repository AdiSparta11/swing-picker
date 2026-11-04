package com.swingpicker.data;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.domain.Bar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily bars only change once a day, so one fetch per symbol per TTL is plenty; live bars get a short TTL.
 * In-memory on purpose: Render's free instance loses its disk anyway, and a restart just means one slow first click.
 * Failures are not cached, so a transient Yahoo error retries on the next request.
 */
public class CachingMarketDataProvider implements MarketDataProvider {

    private record Entry<T>(T value, Instant fetchedAt) {
    }

    private final MarketDataProvider delegate;
    private final Duration ttl;
    private final Duration liveTtl;
    private final Clock clock;
    private final Map<String, Entry<List<Bar>>> cache = new ConcurrentHashMap<>();
    private final Map<String, Entry<Optional<Bar>>> liveCache = new ConcurrentHashMap<>();

    public CachingMarketDataProvider(MarketDataProvider delegate, Duration ttl, Clock clock) {
        this(delegate, ttl, Duration.ofMinutes(3), clock);
    }

    public CachingMarketDataProvider(MarketDataProvider delegate, Duration ttl, Duration liveTtl, Clock clock) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.liveTtl = liveTtl;
        this.clock = clock;
    }

    @Override
    public List<Bar> dailyBars(String symbol, int years) {
        String key = symbol.trim().toUpperCase() + "|" + years;
        Instant now = clock.instant();
        Entry<List<Bar>> hit = cache.get(key);
        if (hit != null && hit.fetchedAt().plus(ttl).isAfter(now)) {
            return hit.value();
        }
        List<Bar> bars = List.copyOf(delegate.dailyBars(symbol, years));
        cache.put(key, new Entry<>(bars, now));
        return bars;
    }

    @Override
    public Optional<Bar> liveBar(String symbol) {
        String key = symbol.trim().toUpperCase();
        Instant now = clock.instant();
        Entry<Optional<Bar>> hit = liveCache.get(key);
        if (hit != null && hit.fetchedAt().plus(liveTtl).isAfter(now)) {
            return hit.value();
        }
        Optional<Bar> live = delegate.liveBar(symbol);
        liveCache.put(key, new Entry<>(live, now));
        return live;
    }
}
