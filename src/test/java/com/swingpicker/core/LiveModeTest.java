package com.swingpicker.core;

import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.core.momentum.MomentumResult;
import com.swingpicker.core.momentum.MomentumService;
import com.swingpicker.data.MarketDataException;
import com.swingpicker.data.YahooChartParser;
import com.swingpicker.domain.Bar;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveModeTest {

    static Clock at(int y, int m, int d, int hh, int mm) {
        ZonedDateTime t = LocalDateTime.of(y, m, d, hh, mm).atZone(LiveSession.IST);
        return Clock.fixed(t.toInstant(), LiveSession.IST);
    }

    static long open(LocalDate d) {
        return d.atTime(9, 15).atZone(LiveSession.IST).toEpochSecond();
    }

    // ---------- parser ----------

    @Test
    void indexBarsWithZeroOrMissingVolumeAreKept() {
        String json = """
            {"chart":{"result":[{"meta":{"symbol":"^NSEI","instrumentType":"INDEX"},
              "timestamp":[%d,%d],
              "indicators":{"quote":[{"open":[100,101],"high":[102,103],"low":[99,100],"close":[101,102],"volume":[0,null]}],
                            "adjclose":[{"adjclose":[101,102]}]}}],"error":null}}
            """.formatted(open(LocalDate.of(2026, 11, 3)), open(LocalDate.of(2026, 11, 4)));

        List<Bar> bars = YahooChartParser.parse(json, ZonedDateTime.of(2026, 11, 4, 23, 0, 0, 0, LiveSession.IST));

        assertEquals(2, bars.size());
    }

    @Test
    void todaysBarIsLiveDuringTheSessionAndCompletedAfter() {
        String json = """
            {"chart":{"result":[{"meta":{"symbol":"TCS.NS","instrumentType":"EQUITY"},
              "timestamp":[%d,%d],
              "indicators":{"quote":[{"open":[100,101],"high":[102,106],"low":[99,100],"close":[101,105],"volume":[10,20]}],
                            "adjclose":[{"adjclose":[101,105]}]}}],"error":null}}
            """.formatted(open(LocalDate.of(2026, 11, 4)), open(LocalDate.of(2026, 11, 5)));

        YahooChartParser.Parsed during = YahooChartParser.parseAll(json, ZonedDateTime.of(2026, 11, 5, 11, 0, 0, 0, LiveSession.IST));
        YahooChartParser.Parsed after = YahooChartParser.parseAll(json, ZonedDateTime.of(2026, 11, 5, 16, 30, 0, 0, LiveSession.IST));

        assertEquals(1, during.completed().size());
        assertEquals(105.0, during.live().get().close(), 1e-9);
        assertEquals(2, after.completed().size());
        assertTrue(after.live().isEmpty());
    }

    // ---------- session ----------

    static MarketDataProvider withLive(Map<String, List<Bar>> completed, Map<String, Bar> live) {
        return new MarketDataProvider() {
            public List<Bar> dailyBars(String symbol, int years) {
                return Optional.ofNullable(completed.get(symbol)).orElseThrow(() -> new MarketDataException("none"));
            }

            public Optional<Bar> liveBar(String symbol) {
                return Optional.ofNullable(live.get(symbol));
            }
        };
    }

    @Test
    void openOnlyOnWeekdaysBetween915And1530() {
        MarketDataProvider any = (s, y) -> List.of();
        assertTrue(new LiveSession(any, at(2026, 11, 5, 9, 15)).isOpen());       // Thursday
        assertTrue(new LiveSession(any, at(2026, 11, 5, 15, 29)).isOpen());
        assertFalse(new LiveSession(any, at(2026, 11, 5, 15, 30)).isOpen());
        assertFalse(new LiveSession(any, at(2026, 11, 5, 9, 14)).isOpen());
        assertFalse(new LiveSession(any, at(2026, 11, 7, 11, 0)).isOpen());      // Saturday
        assertFalse(LiveSession.closed().isOpen());
    }

    @Test
    void liveBarIsAddedOnlyWhileOpenAndOnlyIfItIsToday() {
        LocalDate today = LocalDate.of(2026, 11, 5);
        List<Bar> done = List.of(new Bar(today.minusDays(1), 1, 1, 1, 1, 1));
        Bar now = new Bar(today, 2, 2, 2, 2, 1);
        MarketDataProvider data = withLive(Map.of("X", done), Map.of("X", now, "OLD", done.get(0)));

        assertEquals(2, LiveSession.extend(done, new LiveSession(data, at(2026, 11, 5, 11, 0)).live("X")).size());
        assertEquals(1, LiveSession.extend(done, new LiveSession(data, at(2026, 11, 5, 17, 0)).live("X")).size());
        assertTrue(new LiveSession(data, at(2026, 11, 5, 11, 0)).live("OLD").isEmpty());
    }

    @Test
    void regimeDoesNotRetryAFailingSourceOnEveryQuestion() {
        AtomicInteger calls = new AtomicInteger();
        MarketRegime regime = new MarketRegime((s, y) -> {
            calls.incrementAndGet();
            throw new MarketDataException("down");
        }, 5);

        for (int k = 0; k < 1000; k++) {
            regime.isUp(LocalDate.of(2026, 1, 1));
        }
        assertEquals(1, calls.get());
    }

    // ---------- momentum ----------

    static List<Bar> rising(LocalDate start, int days, double daily) {
        List<Bar> bars = new ArrayList<>();
        double c = 100;
        for (int k = 0; k < days; k++) {
            double o = c;
            c = c * (1 + daily) * (1 + (k % 2 == 0 ? 0.002 : -0.002));
            bars.add(new Bar(start.plusDays(k), o, Math.max(o, c), Math.min(o, c), c, 1000));
        }
        return bars;
    }

    @Test
    void ranksEvenWhenTheNiftysLatestDateDiffersFromTheStocks() {
        // Reproduces the bug: stocks end a day later than the index series.
        LocalDate start = LocalDate.of(2023, 1, 2);
        int days = 900;
        Map<String, List<Bar>> completed = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            uni.add(new Stock("S" + k, "sec" + k));
            completed.put("S" + k, rising(start, days, 0.0005 * (k + 1)));
        }
        completed.put(MarketRegime.NIFTY, rising(start, days - 1, 0.0008));
        MarketDataProvider data = withLive(completed, Map.of());
        MomentumService svc = new MomentumService(uni, data, new MarketRegime(data, 5), new NseDeliveryCostModel(),
                new MomentumService.Config(5000, 10, 21, 2.0, 5));

        MomentumResult r = svc.recommend(10_000);

        assertEquals(2, r.picks().size());
        assertEquals("S3", r.picks().get(0).symbol());
        assertEquals(start.plusDays(days - 1), r.asOf());
        assertFalse(r.livePrices());
    }

    @Test
    void duringMarketHoursTheRankingUsesLivePrices() {
        LocalDate start = LocalDate.of(2023, 1, 2);
        int days = 900;
        LocalDate today = start.plusDays(days);       // the day after the last completed bar
        Map<String, List<Bar>> completed = new HashMap<>();
        Map<String, Bar> live = new HashMap<>();
        List<Stock> uni = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            uni.add(new Stock("S" + k, "sec" + k));
            List<Bar> b = rising(start, days, 0.0005 * (k + 1));
            completed.put("S" + k, b);
            double last = b.get(b.size() - 1).close();
            live.put("S" + k, new Bar(today, last, last * 1.01, last * 0.99, last * 1.005, 500));
        }
        completed.put(MarketRegime.NIFTY, rising(start, days, 0.0008));
        MarketDataProvider data = withLive(completed, live);
        LiveSession session = new LiveSession(data, at(today.getYear(), today.getMonthValue(), today.getDayOfMonth(), 11, 0));
        assertTrue(session.isOpen(), "synthetic today (2025-06-20) is a Friday at 11:00");
        MomentumService svc = new MomentumService(uni, data, new MarketRegime(data, 5, session), new NseDeliveryCostModel(),
                new MomentumService.Config(5000, 10, 21, 2.0, 5), session);

        MomentumResult r = svc.recommend(10_000);

        assertTrue(r.livePrices());
        assertEquals(today, r.asOf());
        assertEquals(live.get(r.picks().get(0).symbol()).close(), r.picks().get(0).lastClose(), 1e-9);
        assertTrue(r.howToHold().startsWith("Prices are live"));
    }
}
