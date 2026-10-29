package com.swingpicker.core;

import com.swingpicker.domain.Edge;

/** Step 5: replays a setup over history and reports how it actually played out. */
public interface EdgeEstimator {
    Edge estimate(Setup setup);
}
