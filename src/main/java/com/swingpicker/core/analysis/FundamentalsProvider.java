package com.swingpicker.core.analysis;

import java.util.Optional;

/** Analyst consensus and valuation for an NSE symbol; empty when the source has none or can't be reached. */
public interface FundamentalsProvider {
    Optional<Consensus> consensus(String symbol);

    static FundamentalsProvider none() {
        return symbol -> Optional.empty();
    }
}
