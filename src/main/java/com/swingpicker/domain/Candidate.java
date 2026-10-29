package com.swingpicker.domain;

/** A stock where a setup fired today, with its historical edge and its score after costs. */
public record Candidate(TradePlan plan, String sector, Edge edge, double expectedNetR) {
}
