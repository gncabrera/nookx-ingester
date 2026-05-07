package com.nookx.ingester.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class HashUtils {

    private HashUtils() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static String sha256(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not compute SHA-256", ex);
        }
    }

    public static String sha256(final String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
