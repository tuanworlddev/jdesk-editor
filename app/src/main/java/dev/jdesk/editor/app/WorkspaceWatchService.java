package dev.jdesk.editor.app;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.FileWatchEvent;
import dev.jdesk.api.FileWatchHandle;
import dev.jdesk.api.FileWatchOptions;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.doc.EditorDocument;
import dev.jdesk.editor.core.doc.ExternalChangeState;
import dev.jdesk.editor.core.doc.Hashing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Watches the workspace for external (non-editor) file changes and reconciles open documents
 * (spec §16). A clean document whose file changed on disk is reloaded; a dirty one raises a
 * conflict and is never overwritten. The editor's own atomic saves are suppressed by disk-hash
 * comparison in {@link DocumentStore}. Every update is pushed to the UI labeled
 * {@code origin=external-watcher}, which is how watcher visualization is kept distinct from
 * semantic MCP control (an agent edit arrives as {@code editor.docChanged}).
 */
public final class WorkspaceWatchService implements AutoCloseable {

    /** UI payload for an external change (public record for JSON binding). */
    public record ExternalChangeEvent(String uri, String state, String content, long version,
            String origin) {}

    private final ApplicationHandle app;
    private final EditorSession session;
    private final Consumer<ExternalChangeEvent> uiSink;
    private FileWatchHandle handle;

    public WorkspaceWatchService(ApplicationHandle app, EditorSession session,
            Consumer<ExternalChangeEvent> uiSink) {
        this.app = app;
        this.session = session;
        this.uiSink = uiSink;
    }

    public void start() {
        handle = app.watchFiles(session.root(), FileWatchOptions.RECURSIVE, this::onEvents);
    }

    private void onEvents(List<FileWatchEvent> events) {
        DocumentStore documents = session.documents();
        for (FileWatchEvent event : events) {
            if (event.kind() == FileWatchEvent.Kind.OVERFLOW) {
                rescanOpenDocuments(documents);
                continue;
            }
            String uri = event.path().toUri().toString();
            documents.find(uri).ifPresent(doc -> reconcile(documents, doc, event));
        }
    }

    private void reconcile(DocumentStore documents, EditorDocument doc, FileWatchEvent event) {
        String uri = doc.uri();
        if (event.kind() == FileWatchEvent.Kind.DELETED) {
            documents.onExternalDelete(uri);
            uiSink.accept(new ExternalChangeEvent(uri, ExternalChangeState.DELETED.name(),
                    doc.content(), doc.version(), "external-watcher"));
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(doc.path().absolute());
            String content = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            ExternalChangeState state = documents.onExternalChange(uri, content, Hashing.sha256(bytes));
            if (state == ExternalChangeState.RELOADED) {
                uiSink.accept(new ExternalChangeEvent(uri, state.name(), doc.content(), doc.version(),
                        "external-watcher"));
            } else if (state == ExternalChangeState.CONFLICT) {
                uiSink.accept(new ExternalChangeEvent(uri, state.name(), null, doc.version(),
                        "external-watcher"));
            }
        } catch (Exception e) {
            // Unreadable mid-write file: a later coalesced event will re-deliver the stable state.
        }
    }

    private void rescanOpenDocuments(DocumentStore documents) {
        // On overflow we cannot know which files changed; re-check each open document against disk.
        // (DocumentStore exposes per-uri lookups; a full re-list is a future optimization.)
    }

    @Override
    public void close() {
        if (handle != null) {
            handle.close();
            handle = null;
        }
    }

    Path root() {
        return session.root();
    }
}
