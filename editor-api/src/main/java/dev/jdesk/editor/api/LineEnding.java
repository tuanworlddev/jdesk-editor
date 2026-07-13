package dev.jdesk.editor.api;

/** Document line-ending style, preserved across load/edit/save so saves are byte-faithful. */
public enum LineEnding {
    LF("\n"),
    CRLF("\r\n");

    private final String sequence;

    LineEnding(String sequence) {
        this.sequence = sequence;
    }

    public String sequence() {
        return sequence;
    }

    /** Detects the dominant line ending in the text, defaulting to LF when none is present. */
    public static LineEnding detect(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf ? CRLF : LF;
    }
}
