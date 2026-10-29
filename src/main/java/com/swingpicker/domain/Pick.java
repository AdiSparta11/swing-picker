package com.swingpicker.domain;

/** A candidate turned into an order: how many shares and how many rupees. */
public record Pick(Candidate candidate, int quantity, double amount, double riskRupees) {
}
