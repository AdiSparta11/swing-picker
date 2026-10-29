package com.swingpicker.core;

import com.swingpicker.domain.Edge;

/**
 * The sort key for "best outcome possibility": expected R per trade AFTER costs.
 * This is the A-vs-B drill. Derive it before filling it in.
 */
public interface Ranker {
    /**
     * @param edge  historical win rate and average win/loss in R
     * @param costR round-trip cost expressed in R for this specific trade
     */
    double expectedNetR(Edge edge, double costR);
}
