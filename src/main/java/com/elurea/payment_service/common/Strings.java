package com.elurea.payment_service.common;

import java.util.Locale;

/**
 * Normalisation helpers applied to user-supplied text before it is persisted.
 */
public final class Strings {

    private Strings() {
    }

    /** Strips surrounding whitespace; returns null for null or blank input. */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Emails are compared case-insensitively, so they are stored trimmed and lower-cased. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
