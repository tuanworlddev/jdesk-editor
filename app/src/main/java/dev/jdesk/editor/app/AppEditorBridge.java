package dev.jdesk.editor.app;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.core.doc.EditorDocument;
import dev.jdesk.editor.mcp.CoreEditorBridge;
import dev.jdesk.editor.mcp.EditorBridge;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link EditorBridge} for the running app: resolves the currently-open {@link EditorSession} per
 * call (so opening a different folder just works) and, whenever an agent changes a document, pushes
 * the new content to the live Monaco model through a UI-event sink. This is what makes an agent's
 * MCP edit appear in the real editor, not just on disk.
 */
public final class AppEditorBridge implements EditorBridge {

    /** Payload for the {@code editor.docChanged} window event (public record for JSON binding). */
    public record DocChangedEvent(String uri, String content, long version) {}

    private final Supplier<EditorSession> session;
    private final Consumer<DocChangedEvent> uiSink;

    public AppEditorBridge(Supplier<EditorSession> session, Consumer<DocChangedEvent> uiSink) {
        this.session = session;
        this.uiSink = uiSink;
    }

    private EditorBridge delegate() {
        EditorSession current = session.get();
        if (current == null) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_ACTIONABLE, "No workspace is open");
        }
        return new CoreEditorBridge(current.paths(), current.fileTree(), current.documents(),
                uri -> pushToUi(current, uri));
    }

    private void pushToUi(EditorSession current, String uri) {
        current.documents().find(uri).ifPresent((EditorDocument doc) ->
                uiSink.accept(new DocChangedEvent(uri, doc.content(), doc.version())));
    }

    @Override public WorkspaceInfo workspace() { return delegate().workspace(); }
    @Override public List<EntryInfo> list(String relPath) { return delegate().list(relPath); }
    @Override public List<EntryInfo> search(String q, int max) { return delegate().search(q, max); }
    @Override public OperationResult createFile(String relPath) { return delegate().createFile(relPath); }
    @Override public DocumentInfo open(String relPath) { return delegate().open(relPath); }
    @Override public OperationResult applyWorkspaceEdit(String relPath,
            List<dev.jdesk.editor.api.wire.TextEditDto> edits, String agentId) {
        return delegate().applyWorkspaceEdit(relPath, edits, agentId);
    }
    @Override public OperationResult save(String relPath) { return delegate().save(relPath); }
    @Override public List<DiagnosticInfo> diagnostics(String relPath) { return delegate().diagnostics(relPath); }
}
