package com.swingpicker.core;

import com.swingpicker.domain.Edge;

/** Step 5: how a setup has historically played out. */
public interface EdgeEstimator {

    /** This stock's record, blended toward the universe's when the stock's own history is short. */
    Edge estimate(Setup setup, String symbol);

    /** The setup's record across the whole universe. */
    Edge pool(Setup setup);
}
