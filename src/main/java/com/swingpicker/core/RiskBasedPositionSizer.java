package com.swingpicker.core;

import com.swingpicker.domain.Candidate;
import com.swingpicker.domain.Pick;
import com.swingpicker.domain.TradePlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Best-first, each stock gets the same rupee risk; whole shares only.
 *
 * Quantity is computed at the worst acceptable fill (maxEntry), so cash always fits:
 *   qty = min( floor(riskBudget / (maxEntry - stop)),  floor(min(cashLeft, positionCap) / maxEntry) )
 * A position is skipped if, after the fixed per-stock charge (DP) is spread over its rupee risk,
 * its expected net R no longer clears the minimum.
 */
public class RiskBasedPositionSizer implements PositionSizer {

    /**
     * @param riskFraction        rupees risked per stock as a fraction of the amount (0.02 = 2%)
     * @param maxPositionFraction cap on one stock's cost as a fraction of the amount
     * @param maxPositions        at most this many picks
     * @param minExpectedNetR     same bar the ranking used, re-checked after fixed costs
     */
    public record Limits(double riskFraction, double maxPositionFraction, int maxPositions, double minExpectedNetR) {
    }

    private final CostModel costs;
    private final Limits limits;

    public RiskBasedPositionSizer(CostModel costs, Limits limits) {
        this.costs = costs;
        this.limits = limits;
    }

    @Override
    public List<Pick> size(List<Candidate> rankedBestFirst, double amount) {
        double riskBudget = amount * limits.riskFraction();
        double positionCap = amount * limits.maxPositionFraction();
        double cash = amount;
        List<Pick> picks = new ArrayList<>();

        for (Candidate c : rankedBestFirst) {
            if (picks.size() >= limits.maxPositions()) {
                break;
            }
            TradePlan p = c.plan();
            double worstRisk = p.maxEntry() - p.stop();
            if (worstRisk <= 0) {
                continue;
            }
            int byRisk = (int) Math.floor(riskBudget / worstRisk);
            int byCash = (int) Math.floor(Math.min(cash, positionCap) / p.maxEntry());
            int qty = Math.min(byRisk, byCash);
            if (qty < 1) {
                continue;
            }
            double netR = c.expectedNetR() - costs.fixedCostPerStock() / (qty * p.riskPerShare());
            if (netR <= limits.minExpectedNetR()) {
                continue;
            }
            cash -= qty * p.maxEntry();
            picks.add(new Pick(c, qty, qty * p.entry(), qty * p.riskPerShare(), netR));
        }
        return picks;
    }
}
