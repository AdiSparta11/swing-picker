package com.swingpicker.core;

import com.swingpicker.domain.Edge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostsAndRankingTest {

    @Test
    void deliveryRoundTripOnFiveThousandEachSide() {
        NseDeliveryCostModel costs = new NseDeliveryCostModel();

        // STT 5 + 5, stamp 0.75, per side txn 0.1535 + SEBI 0.005 + GST 18% of those.
        double perSide = 0.1535 + 0.005 + 0.18 * (0.1535 + 0.005);
        assertEquals(10 + 0.75 + 2 * perSide, costs.variableCost(500, 500, 10), 1e-9);
        assertEquals(15.34, costs.fixedCostPerStock(), 1e-9);
    }

    @Test
    void higherWinRateCanStillRankLower() {
        ExpectedNetRRanker ranker = new ExpectedNetRRanker();
        double a = ranker.expectedNetR(new Edge(0.55, 2.0, 1.0, 50, 500), 0.05);
        double b = ranker.expectedNetR(new Edge(0.65, 1.0, 1.0, 50, 500), 0.05);

        assertEquals(0.60, a, 1e-9);
        assertEquals(0.25, b, 1e-9);
        assertTrue(a > b);
    }
}
