package dev.jdesk.editor.core.doc;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.wire.TextEditDto;
import dev.jdesk.editor.core.fs.PathService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Version conflicts, leases, save, and external-change handling — the spec §11 state machine. */
class DocumentStoreTest {

    private DocumentStore store(Path root) {
        return new DocumentStore(new PathService(root));
    }

    private void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void opensAndTracksVersionAndHash(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello\n");
        DocumentStore store = store(root);

        EditorDocument doc = store.open("a.txt");

        assertThat(doc.version()).isEqualTo(1);
        assertThat(doc.dirty()).isFalse();
        assertThat(doc.contentHash()).isEqualTo(Hashing.sha256("hello\n"));
    }

    @Test
    void editBumpsVersionAndMarksDirty(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");

        DocumentStore.EditResult result = store.applyEdits(doc.uri(), 1,
                List.of(new TextEditDto(1, 6, 1, 6, " world")), Lease.human());

        assertThat(result.version()).isEqualTo(2);
        assertThat(doc.content()).isEqualTo("hello world\n");
        assertThat(doc.dirty()).isTrue();
    }

    @Test
    void staleVersionIsRejectedWithoutPartialWrite(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.applyEdits(doc.uri(), 1, List.of(new TextEditDto(1, 1, 1, 1, "X")), Lease.human());

        EditorException ex = catchThrowableOfType(EditorException.class,
                () -> store.applyEdits(doc.uri(), 1, // stale: version is now 2
                        List.of(new TextEditDto(1, 1, 1, 1, "Y")), Lease.human()));

        assertThat(ex.code()).isEqualTo(EditorErrorCode.DOCUMENT_VERSION_CONFLICT);
        assertThat(doc.content()).isEqualTo("Xhello\n"); // Y never applied
        assertThat(doc.version()).isEqualTo(2);
    }

    @Test
    void agentWriteIsRefusedWhileHumanHoldsLease(@TempDir Path root) throws IOException {
        write(root, "a.txt", "x\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.acquireLease(doc.uri(), Lease.human());

        EditorException ex = catchThrowableOfType(EditorException.class,
                () -> store.applyEdits(doc.uri(), 1,
                        List.of(new TextEditDto(1, 1, 1, 1, "Z")), Lease.agent("codex-1")));

        assertThat(ex.code()).isEqualTo(EditorErrorCode.EDIT_LEASE_CONFLICT);
    }

    @Test
    void twoAgentsCannotBothWrite(@TempDir Path root) throws IOException {
        write(root, "a.txt", "x\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.acquireLease(doc.uri(), Lease.agent("codex-1"));

        EditorException ex = catchThrowableOfType(EditorException.class,
                () -> store.applyEdits(doc.uri(), 1,
                        List.of(new TextEditDto(1, 1, 1, 1, "Z")), Lease.agent("claude-2")));

        assertThat(ex.code()).isEqualTo(EditorErrorCode.EDIT_LEASE_CONFLICT);
    }

    @Test
    void humanMayInterruptAnAgentLease(@TempDir Path root) throws IOException {
        write(root, "a.txt", "x\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.acquireLease(doc.uri(), Lease.agent("codex-1"));

        // Human write is always permitted and takes ownership.
        store.applyEdits(doc.uri(), 1, List.of(new TextEditDto(1, 1, 1, 1, "H")), Lease.human());
        store.acquireLease(doc.uri(), Lease.human());

        assertThat(doc.content()).isEqualTo("Hx\n");
        assertThat(doc.lease().kind()).isEqualTo(Lease.Kind.HUMAN);
    }

    @Test
    void saveWritesBytesAndClearsDirty(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.applyEdits(doc.uri(), 1, List.of(new TextEditDto(1, 6, 1, 6, "!")), Lease.human());

        AtomicSaver.SaveOutcome outcome = store.save(doc.uri(), 2, false);

        assertThat(doc.dirty()).isFalse();
        assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("hello!\n");
        assertThat(outcome.diskHash()).isEqualTo(Hashing.sha256("hello!\n"));
        assertThat(doc.diskHash()).isEqualTo(outcome.diskHash());
    }

    @Test
    void savePreservesCrlfLineEndings(@TempDir Path root) throws IOException {
        write(root, "win.txt", "a\r\nb\r\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("win.txt");
        // Internally normalized to LF; edit then save must restore CRLF bytes.
        store.applyEdits(doc.uri(), 1, List.of(new TextEditDto(2, 2, 2, 2, "!")), Lease.human());

        store.save(doc.uri(), 2, false);

        assertThat(Files.readString(root.resolve("win.txt"))).isEqualTo("a\r\nb!\r\n");
    }

    @Test
    void externalChangeToCleanDocumentReloads(@TempDir Path root) throws IOException {
        write(root, "a.txt", "v1\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");

        ExternalChangeState state = store.onExternalChange(doc.uri(), "v2\n", Hashing.sha256("v2\n"));

        assertThat(state).isEqualTo(ExternalChangeState.RELOADED);
        assertThat(doc.content()).isEqualTo("v2\n");
    }

    @Test
    void externalChangeToDirtyDocumentConflictsAndDoesNotOverwrite(@TempDir Path root) throws IOException {
        write(root, "a.txt", "v1\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        store.applyEdits(doc.uri(), 1, List.of(new TextEditDto(1, 1, 1, 1, "EDIT")), Lease.human());

        ExternalChangeState state = store.onExternalChange(doc.uri(), "v2\n", Hashing.sha256("v2\n"));

        assertThat(state).isEqualTo(ExternalChangeState.CONFLICT);
        assertThat(doc.content()).isEqualTo("EDITv1\n"); // buffer preserved, not overwritten
    }

    @Test
    void selfSaveDoesNotTriggerReload(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello\n");
        DocumentStore store = store(root);
        EditorDocument doc = store.open("a.txt");
        AtomicSaver.SaveOutcome outcome = store.save(doc.uri(), 1, false);

        // The watcher observes our own write; the disk hash matches, so no reload.
        ExternalChangeState state = store.onExternalChange(doc.uri(), "hello\n", outcome.diskHash());

        assertThat(state).isEqualTo(ExternalChangeState.NONE);
    }
}
