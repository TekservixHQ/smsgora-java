package com.tekservix.smsgora;

import com.google.gson.JsonElement;

/** Thrown when the SMS Gora API answers with an error. */
public class SmsGoraException extends RuntimeException {
    private final int status;
    private final JsonElement errors;
    private final String body;
    private final Double retryAfter;

    public SmsGoraException(String message, int status, JsonElement errors, String body, Double retryAfter) {
        super(message);
        this.status = status;
        this.errors = errors;
        this.body = body;
        this.retryAfter = retryAfter;
    }

    public SmsGoraException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.errors = null;
        this.body = null;
        this.retryAfter = null;
    }

    /** HTTP status code (0 for network errors). */
    public int getStatus() { return status; }

    /** Field errors, if the API returned any. */
    public JsonElement getErrors() { return errors; }

    /** Raw response body. */
    public String getBody() { return body; }

    /** Seconds to wait, sent with HTTP 429 (null otherwise). */
    public Double getRetryAfter() { return retryAfter; }
}
