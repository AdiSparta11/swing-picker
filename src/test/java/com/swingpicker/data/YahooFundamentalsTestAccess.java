package com.swingpicker.data;

import com.swingpicker.core.analysis.Consensus;

import java.io.IOException;
import java.util.Optional;

/** Exposes the package-private parser to tests in other packages. */
public final class YahooFundamentalsTestAccess {
    private YahooFundamentalsTestAccess() {
    }

    public static Optional<Consensus> parse(String json) throws IOException {
        return YahooFundamentals.parse(json);
    }
}
