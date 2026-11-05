package com.swingpicker.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyTest {
    @Test
    void indianGrouping() {
        assertEquals("₹0", Money.rupees(0.2));
        assertEquals("₹999", Money.rupees(999));
        assertEquals("₹2,000", Money.rupees(2000));
        assertEquals("₹1,20,772", Money.rupees(120772.4));
        assertEquals("₹12,34,56,789", Money.rupees(123456789));
        assertEquals("−₹15,340", Money.rupees(-15340));
    }
}
