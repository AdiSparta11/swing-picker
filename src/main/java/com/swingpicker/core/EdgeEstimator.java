package com.swingpicker.core;

import com.swingpicker.domain.Edge;

/** Step 5: how a setup has historically played out on a given stock. */
public interface EdgeEstimator {
    Edge estimate(Setup setup, String symbol);
}
