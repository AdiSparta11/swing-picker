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

class BreakoutSetupTest {

    private final BreakoutSetup setup = new BreakoutSetup();

    /** 200 days rising from 100 to ~300, then 60 days moving sideways between 296 and 306. */
    static List<Bar> uptrendThenBase() {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        for (int k = 0; k < 200; k++) {
            double c = 100 + k;
            bars.add(new Bar(d.plusDays(k), c - 0.5, c + 2, c - 2, c, 100_000));
        }
        for (int k = 0; k < 60; k++) {
            double c = 300 + (k % 2 == 0 ? 2 : -2);
            bars.add(new Bar(d.plusDays(200 + k), c, c + 4, c - 4, c, 100_000));
        }
        return bars;
    }

    static List<Bar> with(Bar last) {
        List<Bar> bars = new ArrayList<>(uptrendThenBase());
        bars.add(last);
        return bars;
    }

    static Bar day(double open, double high, double low, double close, long volume) {
        return new Bar(LocalDate.of(2025, 9, 18), open, high, low, close, volume);
    }

    @Test
    void firesOnAStrongHighVolumeCloseAboveTheBase() {
        Optional<TradePlan> plan = setup.detect("TEST", with(day(303, 311, 302, 310, 200_000)));

        assertTrue(plan.isPresent());
        TradePlan p = plan.get();
        assertEquals(310.0, p.entry(), 1e-9);
        assertTrue(p.stop() < 302 && p.stop() > 290, "stop under the breakout day's low, was " + p.stop());
        assertEquals(2.0, p.targetInR(), 0.01);
        assertEquals(BreakoutSetup.NAME, p.setupName());
    }

    @Test
    void lightVolumeIsNotABreakout() {
        assertTrue(setup.detect("TEST", with(day(303, 311, 302, 310, 120_000))).isEmpty());
    }

    @Test
    void weakCloseIsNotABreakout() {
        // Traded up to 315 but closed at 307, in the lower part of the day's range.
        assertTrue(setup.detect("TEST", with(day(303, 315, 302, 307, 200_000))).isEmpty());
    }

    @Test
    void closeInsideTheBaseIsNotABreakout() {
        assertTrue(setup.detect("TEST", with(day(300, 305, 299, 305, 200_000))).isEmpty());
    }
}
