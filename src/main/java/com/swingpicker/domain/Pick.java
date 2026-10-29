package com.swingpicker.domain;

/**
 * A candidate turned into an order: how many shares and how many rupees.
 *
 * @param riskRupees   loss if the stop fills at its price (buying at the reference entry)
 * @param netR         expected R per trade after ALL costs, including the fixed charge for this quantity
 */
public record Pick(Candidate candidate, int quantity, double amount, double riskRupees, double netR) {

    /** Profit if the target fills, before charges. */
    public double rewardRupees() {
        return quantity * (candidate.plan().target() - candidate.plan().entry());
    }

    /** Long-run average rupees per trade like this one, after charges. Not a promise for this trade. */
    public double expectedRupees() {
        return netR * riskRupees;
    }
}
