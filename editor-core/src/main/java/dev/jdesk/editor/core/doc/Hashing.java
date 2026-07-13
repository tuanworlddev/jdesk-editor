package dev.jdesk.editor.core.doc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 over document content. The Java authority hashes with {@link MessageDigest}; the
 * frontend uses a pure-JS SHA-256 (no {@code crypto.subtle} on the macOS custom scheme). Both
 * hash the UTF-8 bytes of the exact model text — proven byte-equivalent by the Phase-0 gate
 * (S3-JAVA) — so a content hash computed on either side is directly comparable.
 */
public final class Hashing {

    private Hashing() {}

    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
