package com.swingpicker.api;

import com.swingpicker.data.MarketDataException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** One place that turns known failures into readable JSON instead of stack traces. */
@RestControllerAdvice
public class ApiErrors {

    /** A step we haven't built yet. */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, String>> notBuiltYet(UnsupportedOperationException e) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("pending", e.getMessage()));
    }

    /** Upstream data problem: Yahoo down, throttling, or unknown symbol. */
    @ExceptionHandler(MarketDataException.class)
    public ResponseEntity<Map<String, String>> marketData(MarketDataException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("marketDataError", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
