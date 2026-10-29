package com.swingpicker.domain;

/**
 * A stock where a setup fired on the latest session, and what happened to it.
 *
 * @param picked  true if it became a pick
 * @param status  "Picked", or the plain-words reason it wasn't
 */
public record Considered(Candidate candidate, boolean picked, String status) {
}
