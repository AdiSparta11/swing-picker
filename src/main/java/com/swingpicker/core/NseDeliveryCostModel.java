package com.swingpicker.core;

/**
 * NSE equity delivery charges for a retail account at a zero-brokerage discount broker.
 * Defaults match Zerodha's published schedule (checked Oct 2026); pass other values for another broker.
 *
 * Per side: STT 0.1%, NSE transaction charge 0.00307%, SEBI fee ₹10/crore, GST 18% on (brokerage + txn + SEBI).
 * Buy side only: stamp duty 0.015%. Per stock sold: DP charge ₹15.34 (GST included).
 */
public class NseDeliveryCostModel implements CostModel {

    public record Rates(double brokeragePerOrder, double stt, double exchangeTxn, double sebi, double gst,
                        double stampBuy, double dpPerSell) {
        public static Rates zerodha() {
            return new Rates(0, 0.001, 0.0000307, 0.000001, 0.18, 0.00015, 15.34);
        }
    }

    private final Rates r;

    public NseDeliveryCostModel(Rates rates) {
        this.r = rates;
    }

    public NseDeliveryCostModel() {
        this(Rates.zerodha());
    }

    @Override
    public double variableCost(double buyPrice, double sellPrice, int quantity) {
        double buy = buyPrice * quantity;
        double sell = sellPrice * quantity;
        return side(buy) + side(sell) + buy * r.stampBuy();
    }

    @Override
    public double fixedCostPerStock() {
        // Brokerage is per order (buy + sell), so it's fixed per stock too; zero at Zerodha for delivery.
        return r.dpPerSell() + 2 * r.brokeragePerOrder() * (1 + r.gst());
    }

    private double side(double turnover) {
        double txn = turnover * r.exchangeTxn();
        double sebi = turnover * r.sebi();
        return turnover * r.stt() + txn + sebi + r.gst() * (txn + sebi);
    }
}
