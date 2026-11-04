package com.swingpicker.core;

import com.swingpicker.domain.Bar;
import com.swingpicker.domain.TradePlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRegimeTest {

    static final LocalDate START = LocalDate.of(2024, 1, 1);

    /** 300 days rising by 1, then 100 days falling by 3: up while rising, down once it breaks the 200-day average. */
    static List<Bar> nifty() {
        List<Bar> bars = new ArrayList<>();
        double c = 1000;
        for (int k = 0; k < 400; k++) {
            c += k < 300 ? 1 : -3;
            bars.add(new Bar(START.plusDays(k), c, c + 1, c - 1, c, 1));
        }
        return bars;
    }

    @Test
    void upInTheRiseDownAfterTheBreakAndUnknownBeforeEnoughHistory() {
        Map<LocalDate, Boolean> up = MarketRegime.compute(nifty());

        assertFalse(up.containsKey(START.plusDays(100)));   // not enough history yet
        assertTrue(up.get(START.plusDays(250)));
        assertFalse(up.get(START.plusDays(399)));
    }

    @Test
    void filteredSetupFiresOnlyOnUpDaysAndRenamesThePlan() {
        List<Bar> index = nifty();
        MarketDataProvider data = (symbol, years) -> {
            assertEquals(MarketRegime.NIFTY, symbol);
            return index;
        };
        MarketRegime regime = new MarketRegime(data, 5);
        Setup always = new Setup() {
            public String name() {
                return "always";
            }

            public Optional<TradePlan> detect(String symbol, List<Bar> bars) {
                return Optional.of(new TradePlan(symbol, "always", null, 100, 95, 110, 101, 15));
            }
        };
        RegimeFilteredSetup filtered = new RegimeFilteredSetup(always, regime);
        Bar stockDay = new Bar(START.plusDays(250), 1, 1, 1, 1, 1);
        Bar downDay = new Bar(START.plusDays(399), 1, 1, 1, 1, 1);
        Bar noIndexDay = new Bar(START.plusDays(900), 1, 1, 1, 1, 1);

        assertEquals("always + nifty-uptrend", filtered.detect("X", List.of(stockDay)).get().setupName());
        assertTrue(filtered.detect("X", List.of(downDay)).isEmpty());
        assertTrue(filtered.detect("X", List.of(noIndexDay)).isEmpty());
        assertEquals(START.plusDays(399), regime.latest().get().getKey());
        assertFalse(regime.latest().get().getValue());
    }

    @Test
    void missingIndexDataMeansTheFilterNeverFires() {
        MarketRegime regime = new MarketRegime((s, y) -> {
            throw new com.swingpicker.data.MarketDataException("down");
        }, 5);

        assertTrue(regime.isUp(START.plusDays(250)).isEmpty());
        assertTrue(regime.latest().isEmpty());
    }
}
