package com.swingpicker.core;

import com.swingpicker.domain.Edge;

/**
 * Expected R per trade after costs: p * avgWin - (1 - p) * avgLoss - costR.
 * Win rate alone is the wrong sort key: 55% winners paying 2R (+0.65R) beats 65% winners paying 1R (+0.30R).
 */
public class ExpectedNetRRanker implements Ranker {
    @Override
    public double expectedNetR(Edge edge, double costR) {
        return edge.expectancyR() - costR;
    }
}
