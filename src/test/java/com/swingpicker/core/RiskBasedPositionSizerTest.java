package com.swingpicker.core;

import com.swingpicker.core.RiskBasedPositionSizer.Limits;
import com.swingpicker.domain.Candidate;
import com.swingpicker.domain.Edge;
import com.swingpicker.domain.Pick;
import com.swingpicker.domain.TradePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskBasedPositionSizerTest {

    private final RiskBasedPositionSizer sizer =
            new RiskBasedPositionSizer(new NseDeliveryCostModel(), new Limits(0.02, 0.35, 10, 0.10));

    static Candidate cand(String sym, double entry, double stop, double maxEntry, double netR) {
        TradePlan p = new TradePlan(sym, "t", null, entry, stop, entry + 2 * (entry - stop), maxEntry, 15);
        return new Candidate(p, "x", new Edge(0.5, 2, 1, 10, 500), netR);
    }

    @Test
    void cappedByPositionSizeNotJustRisk() {
        // ₹10,000: risk budget ₹200, cap ₹3,500. Entry 500, stop 480, maxEntry 505 -> worst risk 25.
        // byRisk = 8, byCash = floor(3500 / 505) = 6 -> 6 shares.
        List<Pick> picks = sizer.size(List.of(cand("A", 500, 480, 505, 0.5)), 10_000);

        assertEquals(1, picks.size());
        assertEquals(6, picks.get(0).quantity());
        assertEquals(3000.0, picks.get(0).amount(), 1e-9);
        assertEquals(120.0, picks.get(0).riskRupees(), 1e-9);
        assertEquals(0.5 - 15.34 / 120.0, picks.get(0).netR(), 1e-9);   // DP charge spread over ₹120 of risk
        assertEquals(240.0, picks.get(0).rewardRupees(), 1e-9);
    }

    @Test
    void cashRunsOutAcrossPicks() {
        List<Candidate> four = List.of(cand("A", 500, 480, 505, 0.5), cand("B", 500, 480, 505, 0.5),
                cand("C", 500, 480, 505, 0.5), cand("D", 500, 480, 505, 0.5));

        List<Pick> picks = sizer.size(four, 10_000);
        // 6 shares x 505 = 3,030 reserved each; three fit in 10,000. The fourth could only get 1 share
        // (₹910 left), where the ₹15.34 DP charge is 0.77R of its ₹20 risk, so it's dropped rather than taken.
        assertEquals(3, picks.size());
        double reserved = picks.stream().mapToDouble(p -> p.quantity() * 505.0).sum();
        assertTrue(reserved <= 10_000);
    }

    @Test
    void tinyPositionDroppedWhenFixedChargeEatsTheEdge() {
        // Stock at ₹3,000 with ₹100 risk: only 1 share fits in ₹3,500. DP ₹15.34 / ₹100 risk = 0.15R,
        // and 0.20R expected minus 0.15R is below the 0.10R bar.
        List<Pick> picks = sizer.size(List.of(cand("EXP", 3000, 2900, 3020, 0.20)), 10_000);

        assertTrue(picks.isEmpty());
    }

    @Test
    void stockPricierThanTheCapIsSkipped() {
        assertTrue(sizer.size(List.of(cand("MRF", 120_000, 115_000, 121_000, 0.6)), 10_000).isEmpty());
    }
}
