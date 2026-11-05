package com.swingpicker.core.track;

import com.swingpicker.core.LiveSession;
import com.swingpicker.core.MarketRegime;
import com.swingpicker.core.momentum.MomentumResult;
import com.swingpicker.core.momentum.MomentumResult.MomentumStock;
import com.swingpicker.domain.Bar;
import com.swingpicker.store.MemoryStore;
import com.swingpicker.store.Store.CallRow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackRecordTest {

    static final LocalDate D0 = LocalDate.of(2026, 10, 1);

    /** Day 0 is the signal day; later days given as {open, high, low, close}. */
    static List<Bar> bars(double[]... ohlc) {
        List<Bar> b = new ArrayList<>();
        b.add(new Bar(D0, 100, 100, 100, 100, 1));
        for (int k = 0; k < ohlc.length; k++) {
            b.add(new Bar(D0.plusDays(k + 1), ohlc[k][0], ohlc[k][1], ohlc[k][2], ohlc[k][3], 1));
        }
        return b;
    }

    static CallRow swing() {
        return new CallRow(1, null, "swing", "X", "trend-pullback", D0, 100, 95.0, 110.0, 101.0, 3, true, "open",
                null, null, null, null, null, null, null);
    }

    @Test
    void swingTargetIsGradedWithTheBacktestRules() {
        List<Bar> b = bars(new double[]{100, 104, 99, 103}, new double[]{103, 111, 102, 109}, new double[]{109, 109, 109, 109});
        List<Bar> nifty = bars(new double[]{1000, 1000, 1000, 1000}, new double[]{1000, 1010, 1000, 1010}, new double[]{1010, 1010, 1010, 1010});

        CallRow g = TrackRecordService.grade(swing(), b, nifty).get();

        assertEquals("target", g.status());
        assertEquals(D0.plusDays(1), g.entryDate());
        assertEquals(D0.plusDays(2), g.exitDate());
        assertEquals(0.10, g.returnPct(), 1e-12);
        assertEquals(2.0, g.rMultiple(), 1e-12);
        assertEquals(0.01, g.benchmark(), 1e-12);
    }

    @Test
    void swingGapAboveMaxEntryIsNoFillAndRunningTradeStaysOpen() {
        assertEquals("no-fill", TrackRecordService.grade(swing(), bars(new double[]{103, 104, 102, 103}), List.of()).get().status());
        assertTrue(TrackRecordService.grade(swing(), bars(new double[]{100, 104, 99, 103}), List.of()).isEmpty());
        assertTrue(TrackRecordService.grade(swing(), List.of(), List.of()).isEmpty());
    }

    @Test
    void momentumIsValuedOneHoldingPeriodLaterAgainstTheNifty() {
        CallRow m = new CallRow(2, null, "momentum", "Y", "momentum top 5", D0, 100, null, null, null, 2, false, "open",
                null, null, null, null, null, null, null);
        List<Bar> b = bars(new double[]{101, 102, 100, 101}, new double[]{104, 105, 103, 104}, new double[]{106, 107, 105, 106});
        List<Bar> nifty = bars(new double[]{1000, 1, 1, 1}, new double[]{1, 1, 1, 1}, new double[]{1020, 1, 1, 1});

        CallRow g = TrackRecordService.grade(m, b, nifty).get();

        assertEquals("done", g.status());
        assertEquals(106.0 / 101 - 1, g.returnPct(), 1e-12);
        assertEquals(0.02, g.benchmark(), 1e-12);
        assertNull(g.rMultiple());
        assertTrue(TrackRecordService.grade(m, b.subList(0, 3), nifty).isEmpty());
    }

    @Test
    void summariesSplitRecommendedFromEverything() {
        List<CallRow> calls = List.of(
                done("swing", true, 0.05, 1.0, 0.01), done("swing", true, -0.03, -1.0, 0.00),
                done("swing", false, -0.04, -1.0, 0.02),
                new CallRow(9, null, "swing", "Q", "x", D0, 1, 1.0, 1.0, 1.0, 15, true, "open", null, null, null, null, null, null, null));

        TrackRecordService.Summary rec = TrackRecordService.summarise("swing", true, calls);
        TrackRecordService.Summary all = TrackRecordService.summarise("swing", false, calls);

        assertEquals(2, rec.closed());
        assertEquals(1, rec.open());
        assertEquals(0.5, rec.winRate(), 1e-12);
        assertEquals(0.01, rec.avgReturn(), 1e-12);
        assertEquals(0.0, rec.avgR(), 1e-12);
        assertEquals(0.5, rec.beatBenchmark(), 1e-12);   // +5% beat +1%; -3% did not beat 0%
        assertEquals(3, all.closed());
        assertEquals(0, TrackRecordService.summarise("momentum", false, calls).closed());
    }

    static CallRow done(String kind, boolean rec, double ret, double r, double bench) {
        return new CallRow(0, null, kind, "S", "x", D0, 100, 95.0, 110.0, 101.0, 15, rec, ret > 0 ? "target" : "stop",
                D0, 100.0, D0, 100 * (1 + ret), ret, r, bench);
    }

    @Test
    void momentumBatchesAreLoggedOncePerHoldingPeriodAndNeverDuringMarketHours() {
        MemoryStore store = new MemoryStore(Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC));
        Map<String, List<Bar>> none = new HashMap<>();
        TrackRecordService closed = new TrackRecordService(store, (s, y) -> Optional.ofNullable(none.get(s)).orElse(List.of()),
                LiveSession.closed(), 5);
        List<MomentumStock> ranking = new ArrayList<>();
        for (int k = 1; k <= 8; k++) {
            ranking.add(new MomentumStock(k, "S" + k, "x", 1, 0, 0, 100, 0, 0, "", 0, 0, 0));
        }
        MomentumResult first = result(LocalDate.of(2026, 11, 4), ranking);

        assertEquals(5, closed.logMomentum(first, 21));
        assertEquals(0, closed.logMomentum(result(LocalDate.of(2026, 11, 20), ranking), 21));   // same period
        assertEquals(5, closed.logMomentum(result(LocalDate.of(2026, 12, 4), ranking), 21));    // next period

        LiveSession open = new LiveSession((s, y) -> List.of(),
                Clock.fixed(LocalDate.of(2026, 11, 5).atTime(11, 0).atZone(LiveSession.IST).toInstant(), LiveSession.IST));
        TrackRecordService live = new TrackRecordService(store, (s, y) -> List.of(), open, 5);
        assertEquals(0, live.logMomentum(result(LocalDate.of(2027, 1, 10), ranking), 21));
        assertEquals(MarketRegime.NIFTY, "^NSEI");
    }

    static MomentumResult result(LocalDate asOf, List<MomentumStock> ranking) {
        return new MomentumResult(true, "r", true, asOf, false, 2, 5000, List.of(), ranking, List.of(), null, "", null);
    }
}
