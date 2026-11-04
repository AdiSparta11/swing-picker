package com.swingpicker.core;

import com.swingpicker.domain.Edge;
import com.swingpicker.domain.SetupRecord;

/** How a setup has historically played out. */
public interface EdgeEstimator {

    /** This stock's record, blended toward the universe's when the stock's own history is short. */
    Edge estimate(Setup setup, String symbol);

    /** The setup's record across the whole universe, overall and split into earlier years vs the last 12 months. */
    SetupRecord record(Setup setup);
}
