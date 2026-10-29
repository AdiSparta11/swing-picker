package com.swingpicker.core;

import com.swingpicker.domain.Candidate;
import com.swingpicker.domain.Pick;

import java.util.List;

/**
 * Step 4: turns ranked candidates plus "I have X rupees" into share quantities.
 * Must respect: per-trade risk cap, the cash available, whole shares only, and the fact that
 * fixed per-stock costs (DP charge) make tiny positions not worth taking.
 */
public interface PositionSizer {
    List<Pick> size(List<Candidate> rankedBestFirst, double amount);
}
