package com.swingpicker.data;

/** Market data couldn't be fetched or understood. Surfaces to the API as 502 with the reason. */
public class MarketDataException extends RuntimeException {
    public MarketDataException(String message) {
        super(message);
    }

    public MarketDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
