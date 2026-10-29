package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendPullbackSetupTest {

    private final TrendPullbackSetup setup = new TrendPullbackSetup();

    /** Steady uptrend of +1/day with a ±3 daily range, then three down days into the 20-day average. */
    static List<Bar> uptrendWithDip() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (int k = 0; k < 245; k++) {
            double c = 100 + k;
            bars.add(new Bar(d.plusDays(k), c - 0.5, c + 3, c - 3, c, 100_000));
        }
        double[] dipCloses = {340, 336, 333};
        for (int k = 0; k < dipCloses.length; k++) {
            double c = dipCloses[k];
            bars.add(new Bar(d.plusDays(245 + k), c + 2, c + 3, c - 3, c, 100_000));
        }
        return bars;
    }

    static Bar turn(List<Bar> bars, double close) {
        LocalDate next = bars.get(bars.size() - 1).date().plusDays(1);
        return new Bar(next, close - 4, close + 1, close - 5, close, 150_000);
    }

    @Test
    void firesOnTheTurnAfterAPullbackInAnUptrend() {
        List<Bar> bars = new ArrayList<>(uptrendWithDip());
        bars.add(turn(bars, 342));

        Optional<TradePlan> plan = setup.detect("TEST", bars);

        assertTrue(plan.isPresent());
        TradePlan p = plan.get();
        assertEquals(342.0, p.entry(), 1e-9);
        assertTrue(p.stop() < 330 && p.stop() > 320, "stop just under the dip, was " + p.stop());
        assertEquals(2.0, p.targetInR(), 0.01);
        assertTrue(p.maxEntry() > p.entry() && p.maxEntry() < p.target());
        assertEquals(15, p.maxHoldDays());
        assertEquals(0.0, Math.round(p.stop() * 20) - p.stop() * 20, 1e-6); // on a ₹0.05 tick
    }

    @Test
    void noTurnNoSignal() {
        List<Bar> bars = new ArrayList<>(uptrendWithDip());
        bars.add(turn(bars, 334)); // closes below yesterday's high

        assertTrue(setup.detect("TEST", bars).isEmpty());
    }

    @Test
    void downtrendNeverFires() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (int k = 0; k < 250; k++) {
            double c = 400 - k;
            bars.add(new Bar(d.plusDays(k), c + 0.5, c + 3, c - 3, c, 100_000));
        }
        bars.add(turn(bars, 160));

        assertTrue(setup.detect("TEST", bars).isEmpty());
    }

    @Test
    void tooLittleHistoryIsIgnored() {
        List<Bar> bars = new ArrayList<>(uptrendWithDip().subList(100, 248));
        bars.add(turn(bars, 342));

        assertTrue(setup.detect("TEST", bars).isEmpty());
    }
}
