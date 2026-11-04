package com.swingpicker.core;

import com.swingpicker.core.BacktestEdgeEstimator.Trade;
import com.swingpicker.core.RecommendationService.Stock;
import com.swingpicker.domain.Bar;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.SetupRecord;
import com.swingpicker.domain.TradePlan;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEdgeEstimatorTest {

    /** Fires once, on day index 1: entry ref 100, stop 95, target 110, maxEntry 101, hold 3. */
    static final Setup ONCE = new Setup() {
        public String name() {
            return "once";
        }

        public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
            return bars.size() == 2
                    ? Optional.of(new TradePlan(symbol, "once", null, 100, 95, 110, 101, 3))
                    : Optional.empty();
        }
    };

    /** Day 0 and 1 are flat; days from index 2 on are given as {open, high, low, close}. */
    static List<Bar> days(double[]... ohlc) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        bars.add(new Bar(d, 100, 100, 100, 100, 1));
        bars.add(new Bar(d.plusDays(1), 100, 100, 100, 100, 1));
        for (int k = 0; k < ohlc.length; k++) {
            double[] x = ohlc[k];
            bars.add(new Bar(d.plusDays(2 + k), x[0], x[1], x[2], x[3], 1));
        }
        return bars;
    }

    static double onlyR(List<Bar> bars) {
        List<Trade> t = BacktestEdgeEstimator.replay(ONCE, "X", bars);
        assertEquals(1, t.size());
        return t.get(0).r();
    }

    @Test
    void targetHit() {
        // Entry 100 at day-2 open, risk 5, target 110 hit on day 3: +2R.
        assertEquals(2.0, onlyR(days(new double[]{100, 104, 99, 103}, new double[]{103, 111, 102, 109},
                new double[]{109, 109, 109, 109})), 1e-9);
    }

    @Test
    void stopHit() {
        assertEquals(-1.0, onlyR(days(new double[]{100, 101, 94, 96}, new double[]{96, 96, 96, 96},
                new double[]{96, 96, 96, 96})), 1e-9);
    }

    @Test
    void gapThroughStopFillsAtTheOpen() {
        // Day 3 opens at 90, below the 95 stop: lose (90 - 100) / 5 = -2R, not -1R.
        assertEquals(-2.0, onlyR(days(new double[]{100, 102, 98, 99}, new double[]{90, 92, 88, 91},
                new double[]{91, 91, 91, 91})), 1e-9);
    }

    @Test
    void bothTouchedSameDayCountsAsStop() {
        assertEquals(-1.0, onlyR(days(new double[]{100, 112, 94, 105}, new double[]{105, 105, 105, 105},
                new double[]{105, 105, 105, 105})), 1e-9);
    }

    @Test
    void timeExitAtCloseOfLastHoldDay() {
        // Neither level touched in 3 days: exit at day-4 close 103 -> +0.6R.
        assertEquals(0.6, onlyR(days(new double[]{100, 102, 98, 101}, new double[]{101, 104, 99, 102},
                new double[]{102, 104, 100, 103})), 1e-9);
    }

    @Test
    void gapAboveMaxEntrySkipsTheTrade() {
        List<Bar> bars = days(new double[]{102, 104, 101, 103}, new double[]{103, 111, 102, 109},
                new double[]{109, 109, 109, 109});
        assertTrue(BacktestEdgeEstimator.replay(ONCE, "X", bars).isEmpty());
    }

    @Test
    void notEnoughFutureDropsTheTrade() {
        List<Bar> bars = days(new double[]{100, 104, 99, 103}, new double[]{103, 108, 102, 107});
        assertTrue(BacktestEdgeEstimator.replay(ONCE, "X", bars).isEmpty());
    }

    /** Flat 100 for two years, except a +12 jump after each listed day: any setup entering there hits a 110 target. */
    static List<Bar> twoYears(Set<Integer> winDays) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 11, 1);
        for (int k = 0; k < 730; k++) {
            boolean pop = winDays.contains(k - 2);
            bars.add(new Bar(d.plusDays(k), 100, pop ? 112 : 100.5, pop ? 99.5 : 99.5, pop ? 111 : 100, 1));
        }
        return bars;
    }

    /** Fires on the given bar indexes with entry 100, stop 95, target 110, hold 3. */
    static Setup firesOn(String name, Set<Integer> days) {
        return new Setup() {
            public String name() {
                return name;
            }

            public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
                return days.contains(bars.size() - 1)
                        ? Optional.of(new TradePlan(symbol, name, null, 100, 95, 110, 101, 3))
                        : Optional.empty();
            }
        };
    }

    @Test
    void eachSetupGetsItsOwnStatsSplitIntoEarlierAndLast12Months() {
        // Setup A: one win early (day 100), one flat time exit late (day 600).
        // Setup B: three flat time exits, all early. Same stock, same data.
        List<Bar> bars = twoYears(Set.of(100));
        MarketDataProvider fake = (symbol, years) -> bars;
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
        BacktestEdgeEstimator est = new BacktestEdgeEstimator(List.of(new Stock("X", "x")), fake, 5, 20,
                Duration.ofHours(6), clock);

        SetupRecord a = est.record(firesOn("A", Set.of(100, 600)));
        SetupRecord b = est.record(firesOn("B", Set.of(10, 50, 200)));

        assertEquals(2, a.trades());
        assertEquals(1, a.earlierTrades());
        assertEquals(1, a.recentTrades());
        assertEquals(2.0, a.earlierExpectancyR(), 1e-9);
        assertEquals(0.0, a.recentExpectancyR(), 1e-9);
        assertEquals(bars.get(729).date().minusYears(1), a.recentFrom());

        assertEquals(3, b.trades());
        assertEquals(0, b.recentTrades());
        assertEquals(0.0, b.expectancyR(), 1e-9);
        assertTrue(a.expectancyR() != b.expectancyR() && a.avgWinR() != b.avgWinR());
    }

    @Test
    void stockStatsAreBlendedTowardTheUniverse() {
        // Stock A: one +2R win. Stock B: three -1R losses. Pool: 1 win of 4, avgWin 2, avgLoss 1.
        List<Bar> win = days(new double[]{100, 104, 99, 103}, new double[]{103, 111, 102, 109},
                new double[]{109, 109, 109, 109});
        List<Bar> loss = days(new double[]{100, 101, 94, 96}, new double[]{96, 96, 96, 96},
                new double[]{96, 96, 96, 96});
        Map<String, List<Bar>> byStock = Map.of("A", win, "B", loss, "C", loss, "D", loss);
        MarketDataProvider fake = (symbol, years) -> byStock.get(symbol);
        List<Stock> uni = List.of(new Stock("A", "x"), new Stock("B", "x"), new Stock("C", "x"), new Stock("D", "x"));
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC);

        BacktestEdgeEstimator est = new BacktestEdgeEstimator(uni, fake, 3, 4, Duration.ofHours(6), clock);
        Edge a = est.estimate(ONCE, "A");

        // pool p = 0.25; prior worth 4 trades -> p = (1 + 4*0.25) / (1 + 4) = 0.4
        assertEquals(0.4, a.winRate(), 1e-9);
        assertEquals(2.0, a.avgWinR(), 1e-9);
        assertEquals(1.0, a.avgLossR(), 1e-9);
        assertEquals(1, a.stockTrades());
        assertEquals(4, a.poolTrades());
        assertEquals(0.4 * 2 - 0.6 * 1, a.expectancyR(), 1e-9);
    }
}
