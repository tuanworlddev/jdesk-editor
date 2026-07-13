package dev.jdesk.editor.evidence;

/**
 * Structural PNG validation: a screenshot only counts as evidence when it is a real PNG with
 * plausible dimensions, not an empty or mislabeled file (spec section 25 rule 4 support).
 */
public final class PngValidator {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int MIN_BYTES = 1024;

    private PngValidator() {}

    public static boolean isRealPng(byte[] bytes) {
        if (bytes == null || bytes.length < MIN_BYTES) {
            return false;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bytes[i] != SIGNATURE[i]) {
                return false;
            }
        }
        // First chunk must be IHDR: length(4) type(4) at offset 8.
        if (!(bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R')) {
            return false;
        }
        int width = readInt(bytes, 16);
        int height = readInt(bytes, 20);
        return width > 0 && height > 0 && width < 65_536 && height < 65_536;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
