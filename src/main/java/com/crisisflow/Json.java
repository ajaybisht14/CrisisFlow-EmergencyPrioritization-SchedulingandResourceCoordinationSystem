package com.crisisflow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Safe accessors for request bodies. Every problem becomes an
 * IllegalArgumentException (HTTP 400) instead of a NullPointerException (500).
 */
public final class Json {

    private Json() {}

    private static JsonElement field(JsonObject body, String name) {
        if (body == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        return e;
    }

    public static String requireString(JsonObject body, String name, int maxLength) {
        JsonElement e = field(body, name);
        if (!e.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be text.");
        }
        String v = e.getAsString().trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        if (v.length() > maxLength) {
            throw new IllegalArgumentException(
                name + " is too long (max " + maxLength + " characters).");
        }
        return v;
    }

    public static String optionalString(
            JsonObject body, String name, int maxLength) {
        if (body == null || !body.has(name) || body.get(name).isJsonNull()) {
            return "";
        }
        return requireOrEmpty(body, name, maxLength);
    }

    private static String requireOrEmpty(JsonObject body, String name, int max) {
        JsonElement e = body.get(name);
        if (!e.isJsonPrimitive()) {
            throw new IllegalArgumentException(name + " must be text.");
        }
        String v = e.getAsString().trim();
        if (v.length() > max) {
            throw new IllegalArgumentException(
                name + " is too long (max " + max + " characters).");
        }
        return v;
    }

    public static int requireInt(JsonObject body, String name) {
        JsonElement e = field(body, name);
        try {
            double d = e.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (int) d;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(name + " must be a whole number.");
        }
    }

    public static double requireDouble(JsonObject body, String name) {
        JsonElement e = field(body, name);
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(name + " must be a number.");
        }
    }
}
