package com.swingpicker.data;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.domain.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachingMarketDataProviderTest {

    /** A clock the test can move forward. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T05:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    static final class CountingProvider implements MarketDataProvider {
        int calls;
        boolean fail;

        public List<Bar> dailyBars(String symbol, int years) {
            calls++;
            if (fail) {
                throw new MarketDataException("down");
            }
            return List.of(new Bar(LocalDate.parse("2026-10-28"), 1, 2, 1, 2, 10));
        }
    }

    @Test
    void servesFromCacheWithinTtlAndRefetchesAfter() {
        MovableClock clock = new MovableClock();
        CountingProvider yahoo = new CountingProvider();
        CachingMarketDataProvider cached = new CachingMarketDataProvider(yahoo, Duration.ofHours(1), clock);

        cached.dailyBars("RELIANCE", 3);
        cached.dailyBars("reliance ", 3);    // same key after normalising
        assertEquals(1, yahoo.calls);

        cached.dailyBars("RELIANCE", 1);     // different history length is a different key
        assertEquals(2, yahoo.calls);

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        cached.dailyBars("RELIANCE", 3);
        assertEquals(3, yahoo.calls);
    }

    @Test
    void failuresAreNotCached() {
        MovableClock clock = new MovableClock();
        CountingProvider yahoo = new CountingProvider();
        CachingMarketDataProvider cached = new CachingMarketDataProvider(yahoo, Duration.ofHours(1), clock);

        yahoo.fail = true;
        assertThrows(MarketDataException.class, () -> cached.dailyBars("TCS", 3));
        yahoo.fail = false;
        assertEquals(1, cached.dailyBars("TCS", 3).size());
        assertEquals(2, yahoo.calls);
    }
}
