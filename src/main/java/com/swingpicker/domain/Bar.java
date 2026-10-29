package com.swingpicker.domain;

import java.time.LocalDate;

/** One daily candle. Bars are always passed oldest-first, ending at the latest completed session. */
public record Bar(LocalDate date, double open, double high, double low, double close, long volume) {
}
