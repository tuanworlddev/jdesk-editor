package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.core.doc.AtomicSaver;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.doc.EditorDocument;
import dev.jdesk.editor.core.doc.Lease;
import dev.jdesk.editor.app.EditorSession;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Typed IPC facade for document open, human-edit sync, and save. Human edits take the human lease;
 * optimistic-concurrency versioning is enforced in {@link DocumentStore}, so a stale client edit
 * returns a resync signal rather than corrupting the buffer.
 */
public final class DocumentFacade {

    private final Supplier<EditorSession> session;

    public DocumentFacade(Supplier<EditorSession> session) {
        this.session = session;
    }

    @DesktopCommand("doc.open")
    @RequiresCapability("editor:core")
    public CompletionStage<DocumentDtos.DocSnapshot> open(
            DocumentDtos.OpenDocRequest request, InvocationContext context) {
        EditorDocument doc = documents().open(request.relPath());
        return CompletableFuture.completedFuture(snapshot(doc));
    }

    @DesktopCommand("doc.change")
    @RequiresCapability("editor:core")
    public CompletionStage<DocumentDtos.ChangeAck> change(
            DocumentDtos.ChangeRequest request, InvocationContext context) {
        DocumentStore store = documents();
        try {
            DocumentStore.EditResult result =
                    store.applyEdits(request.uri(), request.baseVersion(), request.edits(), Lease.human());
            return CompletableFuture.completedFuture(
                    new DocumentDtos.ChangeAck(result.version(), result.contentHash(), false));
        } catch (EditorException e) {
            if (e.code() == EditorErrorCode.DOCUMENT_VERSION_CONFLICT) {
                // Signal the client to pull a fresh snapshot rather than failing the keystroke path.
                EditorDocument doc = store.find(request.uri()).orElseThrow(() -> e);
                return CompletableFuture.completedFuture(
                        new DocumentDtos.ChangeAck(doc.version(), doc.contentHash(), true));
            }
            throw e;
        }
    }

    @DesktopCommand("doc.save")
    @RequiresCapability("editor:core")
    public CompletionStage<DocumentDtos.SaveResult> save(
            DocumentDtos.SaveRequest request, InvocationContext context) {
        AtomicSaver.SaveOutcome outcome =
                documents().save(request.uri(), request.expectedVersion(), request.force());
        EditorDocument doc = documents().find(request.uri()).orElseThrow();
        return CompletableFuture.completedFuture(
                new DocumentDtos.SaveResult(doc.version(), outcome.diskHash(), outcome.savedAtEpochMs()));
    }

    @DesktopCommand("doc.close")
    @RequiresCapability("editor:core")
    public CompletionStage<DocumentDtos.CloseResult> close(
            DocumentDtos.CloseRequest request, InvocationContext context) {
        EditorDocument doc = documents().find(request.uri()).orElse(null);
        if (doc != null && doc.dirty() && !request.discardDirty()) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_ACTIONABLE,
                    "Document has unsaved changes");
        }
        documents().close(request.uri());
        return CompletableFuture.completedFuture(new DocumentDtos.CloseResult(true));
    }

    private DocumentStore documents() {
        EditorSession current = session.get();
        if (current == null) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_ACTIONABLE, "No workspace is open");
        }
        return current.documents();
    }

    private static DocumentDtos.DocSnapshot snapshot(EditorDocument doc) {
        return new DocumentDtos.DocSnapshot(doc.uri(), doc.path().relative(), doc.version(),
                doc.content(), doc.contentHash(), doc.lineEnding().name(), doc.dirty());
    }
}
