package com.swingpicker.core.analysis;

/**
 * Sell-side analyst consensus and basic valuation for one stock, as published by Yahoo Finance.
 * Any field can be null when Yahoo doesn't have it (many smaller Indian stocks have little or no coverage).
 *
 * @param targetMean       average 12-month price target across analysts
 * @param analysts         number of analysts with a target
 * @param rating           Yahoo's summary of their ratings: strong_buy, buy, hold, underperform, sell
 * @param ratingMean       1 = strong buy ... 5 = sell
 * @param trailingPE       price / last 12 months' earnings per share
 * @param forwardPE        price / next 12 months' forecast EPS
 * @param earningsGrowth   latest quarterly earnings growth, year on year (fraction)
 * @param revenueGrowth    latest quarterly revenue growth, year on year (fraction)
 */
public record Consensus(Double targetMean, Double targetHigh, Double targetLow, Integer analysts, String rating,
                        Double ratingMean, Double trailingPE, Double forwardPE, Double trailingEps, Double forwardEps,
                        Double earningsGrowth, Double revenueGrowth, Double profitMargin, Double debtToEquity) {

    public boolean hasTarget() {
        return targetMean != null && targetMean > 0 && analysts != null && analysts > 0;
    }
}
