package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.wire.TextEditDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies Monaco-style {@link TextEditDto} batches to document content deterministically. Edits
 * within one batch are treated as referring to the <em>original</em> document coordinates (Monaco
 * semantics): they are sorted by position and applied last-to-first so earlier offsets stay valid,
 * and overlaps are rejected. The result must be byte-identical to Monaco applying the same batch —
 * this is the invariant the whole edit-transaction hash check rests on (spec §12.4).
 */
public final class TextEdits {

    private TextEdits() {}

    public static String apply(String content, List<TextEditDto> edits) {
        if (edits.isEmpty()) {
            return content;
        }
        int[] lineStarts = lineStartOffsets(content);
        record Resolved(int start, int end, String text) {}

        List<Resolved> resolved = new ArrayList<>(edits.size());
        for (TextEditDto edit : edits) {
            int start = offsetOf(content, lineStarts, edit.startLine(), edit.startColumn());
            int end = offsetOf(content, lineStarts, edit.endLine(), edit.endColumn());
            resolved.add(new Resolved(start, end, edit.text()));
        }
        resolved.sort(Comparator.comparingInt(Resolved::start).thenComparingInt(Resolved::end));

        // Reject overlaps (ambiguous), then apply from the end so offsets remain valid.
        for (int i = 1; i < resolved.size(); i++) {
            if (resolved.get(i).start() < resolved.get(i - 1).end()) {
                throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                        "Overlapping edits in one transaction are not allowed");
            }
        }
        StringBuilder builder = new StringBuilder(content);
        for (int i = resolved.size() - 1; i >= 0; i--) {
            Resolved r = resolved.get(i);
            builder.replace(r.start(), r.end(), r.text());
        }
        return builder.toString();
    }

    /** UTF-16 offset of a 1-based (line, column) position, clamped to content bounds. */
    private static int offsetOf(String content, int[] lineStarts, int line, int column) {
        if (line < 1 || line > lineStarts.length) {
            throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                    "Line " + line + " out of range (1.." + lineStarts.length + ")");
        }
        int base = lineStarts[line - 1];
        int lineEnd = line < lineStarts.length ? lineStarts[line] : content.length();
        int offset = base + (column - 1);
        if (offset < base) {
            throw new EditorException(EditorErrorCode.INVALID_ARGUMENT, "Column " + column + " < 1");
        }
        return Math.min(offset, lineEnd);
    }

    /** Offsets at which each line begins; index i is the start of line (i+1). */
    private static int[] lineStartOffsets(String content) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }
}
