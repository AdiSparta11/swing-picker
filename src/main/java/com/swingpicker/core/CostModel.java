package com.swingpicker.core;

/**
 * Step 3: NSE delivery charges for a full round trip (buy then sell), in rupees.
 * Split in two because they matter in different places:
 * variable charges scale with the trade, so they affect ranking (cost per R is size-independent);
 * fixed charges are per stock, so they decide whether a small position is worth taking at all.
 */
public interface CostModel {

    /** Charges proportional to turnover: STT, stamp duty, exchange + SEBI fees, GST on those. */
    double variableCost(double buyPrice, double sellPrice, int quantity);

    /** Charges per stock sold regardless of size, e.g. the depository (DP) charge. */
    double fixedCostPerStock();

    default double roundTripCost(double buyPrice, double sellPrice, int quantity) {
        return variableCost(buyPrice, sellPrice, quantity) + fixedCostPerStock();
    }
}
