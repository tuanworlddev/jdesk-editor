package dev.jdesk.editor.api.wire;

/**
 * A single text edit in Monaco's 1-based line / 1-based column coordinate space, replacing the
 * range {@code [start, end)} with {@code text}. A pure insertion has an empty range (start == end);
 * a pure deletion has empty {@code text}. Wire DTO: a public record of scalars only.
 */
public record TextEditDto(
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String text) {

    public TextEditDto {
        if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("Monaco positions are 1-based and must be >= 1");
        }
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException("Edit end must not precede start");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null (use empty string for deletion)");
        }
    }
}
