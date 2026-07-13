package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.wire.TextEditDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edit application must be deterministic and byte-faithful to Monaco (spec §12.4) — the final
 * content hash is compared across the boundary, so any divergence here breaks every edit
 * transaction.
 */
class TextEditsTest {

    @Test
    void appliesASingleInsertion() {
        String result = TextEdits.apply("hello world\n",
                List.of(new TextEditDto(1, 6, 1, 6, ",")));
        assertThat(result).isEqualTo("hello, world\n");
    }

    @Test
    void appliesASingleDeletion() {
        String result = TextEdits.apply("hello world\n",
                List.of(new TextEditDto(1, 6, 1, 12, "")));
        assertThat(result).isEqualTo("hello\n");
    }

    @Test
    void appliesMultipleEditsInOriginalCoordinatesRegardlessOfOrder() {
        // Two edits given out of positional order; both reference the original document.
        String content = "AAAA BBBB CCCC\n";
        List<TextEditDto> edits = List.of(
                new TextEditDto(1, 11, 1, 15, "cccc"), // CCCC -> cccc
                new TextEditDto(1, 1, 1, 5, "aaaa"));   // AAAA -> aaaa
        assertThat(TextEdits.apply(content, edits)).isEqualTo("aaaa BBBB cccc\n");
    }

    @Test
    void appliesEditsAcrossMultipleLines() {
        String content = "line1\nline2\nline3\n";
        List<TextEditDto> edits = List.of(
                new TextEditDto(2, 1, 2, 6, "LINE2"),   // replace "line2" -> "LINE2"
                new TextEditDto(3, 5, 3, 6, "X"));       // replace the '3' in "line3" -> "lineX"
        assertThat(TextEdits.apply(content, edits)).isEqualTo("line1\nLINE2\nlineX\n");
    }

    @Test
    void rejectsOverlappingEdits() {
        assertThatThrownBy(() -> TextEdits.apply("abcdef\n", List.of(
                new TextEditDto(1, 1, 1, 4, "X"),
                new TextEditDto(1, 3, 1, 5, "Y"))))
                .isInstanceOf(EditorException.class)
                .hasMessageContaining("Overlapping");
    }

    @Test
    void resultHashIsStableAndOrderIndependent() {
        String content = "one two three\n";
        List<TextEditDto> a = List.of(
                new TextEditDto(1, 1, 1, 4, "1"),
                new TextEditDto(1, 9, 1, 14, "3"));
        List<TextEditDto> b = List.of(
                new TextEditDto(1, 9, 1, 14, "3"),
                new TextEditDto(1, 1, 1, 4, "1"));
        String ra = TextEdits.apply(content, a);
        String rb = TextEdits.apply(content, b);
        assertThat(ra).isEqualTo(rb);
        assertThat(Hashing.sha256(ra)).isEqualTo(Hashing.sha256(rb));
    }

    @Test
    void emptyBatchIsIdentity() {
        assertThat(TextEdits.apply("unchanged\n", List.of())).isEqualTo("unchanged\n");
    }
}
