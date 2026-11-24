package com.crisisflow;

import java.util.Map;

/** An error that maps directly to an HTTP status (400, 404, 409 ...). */
public class ApiException extends RuntimeException {

    private final int status;
    private final transient Map<String, Object> extra;

    public ApiException(int status, String message) {
        this(status, message, Map.of());
    }

    public ApiException(int status, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.extra = extra;
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }
}
