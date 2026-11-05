package com.swingpicker.core;

/** Rupee amounts in Indian digit grouping (₹1,20,772), rounded to the rupee. */
public final class Money {

    private Money() {
    }

    public static String rupees(double v) {
        long n = Math.round(Math.abs(v));
        String digits = Long.toString(n);
        StringBuilder out = new StringBuilder();
        int len = digits.length();
        if (len <= 3) {
            out.append(digits);
        } else {
            String head = digits.substring(0, len - 3);
            String tail = digits.substring(len - 3);
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    h.append(',');
                }
                h.append(head.charAt(i));
            }
            out.append(h).append(',').append(tail);
        }
        return (v < 0 && n != 0 ? "−₹" : "₹") + out;
    }
}
