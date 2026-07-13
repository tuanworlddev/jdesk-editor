package dev.jdesk.editor.mcp;

import dev.jdesk.editor.api.wire.TextEditDto;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.doc.EditorDocument;
import dev.jdesk.editor.core.doc.Lease;
import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link EditorBridge} backed directly by the editor core. Agent edits take an agent lease (so a
 * human editing the same document takes priority) and, after committing to the authoritative
 * buffer, notify a change listener — the app wires that listener to push the new content into the
 * live Monaco model so an agent's edit appears in the UI.
 */
public final class CoreEditorBridge implements EditorBridge {

    private final PathService paths;
    private final FileTree fileTree;
    private final DocumentStore documents;
    private final Consumer<String> onDocumentChanged;

    public CoreEditorBridge(PathService paths, FileTree fileTree, DocumentStore documents,
            Consumer<String> onDocumentChanged) {
        this.paths = paths;
        this.fileTree = fileTree;
        this.documents = documents;
        this.onDocumentChanged = onDocumentChanged;
    }

    @Override
    public WorkspaceInfo workspace() {
        var root = paths.root();
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        return new WorkspaceInfo(true, name, root.toString());
    }

    @Override
    public List<EntryInfo> list(String relPath) {
        return fileTree.listChildren(relPath == null ? "" : relPath).stream()
                .map(e -> new EntryInfo(e.name(), e.relativePath(), e.directory(), e.hasChildren()))
                .toList();
    }

    @Override
    public List<EntryInfo> search(String query, int maxResults) {
        List<EntryInfo> results = new java.util.ArrayList<>();
        searchInto("", query.toLowerCase(), maxResults, results);
        return results;
    }

    private void searchInto(String relPath, String query, int max, List<EntryInfo> out) {
        if (out.size() >= max) {
            return;
        }
        for (FileTree.Entry entry : fileTree.listChildren(relPath)) {
            if (out.size() >= max) {
                return;
            }
            if (entry.directory()) {
                searchInto(entry.relativePath(), query, max, out);
            } else if (entry.relativePath().toLowerCase().contains(query)) {
                out.add(new EntryInfo(entry.name(), entry.relativePath(), false, false));
            }
        }
    }

    @Override
    public OperationResult createFile(String relPath) {
        EditorDocument doc = documents.create(relPath);
        onDocumentChanged.accept(doc.uri());
        return new OperationResult(newOperationId(), "COMMITTED",
                Map.of(doc.uri(), doc.version()), "Created and opened " + relPath);
    }

    @Override
    public OperationResult renameFile(String fromRelPath, String toRelPath) {
        var from = paths.resolveExisting(fromRelPath);
        paths.ensureParentDirectories(toRelPath);
        var to = paths.resolveForCreate(toRelPath);
        try {
            java.nio.file.Files.move(from.absolute(), to.absolute());
        } catch (java.io.IOException e) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.INTERNAL_ERROR,
                    "Cannot rename " + fromRelPath, e);
        }
        documents.close(from.uri());
        return new OperationResult(newOperationId(), "COMMITTED", Map.of(),
                "Renamed " + fromRelPath + " -> " + toRelPath);
    }

    @Override
    public OperationResult deleteFile(String relPath) {
        var path = paths.resolveExisting(relPath);
        try {
            java.nio.file.Files.delete(path.absolute());
        } catch (java.io.IOException e) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.INTERNAL_ERROR,
                    "Cannot delete " + relPath, e);
        }
        documents.close(path.uri());
        return new OperationResult(newOperationId(), "COMMITTED", Map.of(), "Deleted " + relPath);
    }

    @Override
    public DocumentInfo open(String relPath) {
        EditorDocument doc = documents.open(relPath);
        return new DocumentInfo(doc.uri(), doc.path().relative(), doc.version(),
                doc.contentHash(), doc.content());
    }

    @Override
    public OperationResult applyWorkspaceEdit(String relPath, List<TextEditDto> edits, String agentId) {
        EditorDocument doc = documents.open(relPath);
        documents.acquireLease(doc.uri(), Lease.agent(agentId));
        try {
            DocumentStore.EditResult result =
                    documents.applyEdits(doc.uri(), doc.version(), edits, Lease.agent(agentId));
            onDocumentChanged.accept(doc.uri());
            return new OperationResult(newOperationId(), "COMMITTED",
                    Map.of(doc.uri(), result.version()),
                    "Applied " + edits.size() + " edit(s) to " + relPath);
        } finally {
            documents.releaseLease(doc.uri());
        }
    }

    @Override
    public OperationResult save(String relPath) {
        EditorDocument doc = documents.open(relPath);
        var outcome = documents.save(doc.uri(), doc.version(), false);
        return new OperationResult(newOperationId(), "COMMITTED",
                Map.of(doc.uri(), doc.version()),
                "Saved " + relPath + " (" + outcome.diskHash().substring(0, 12) + "…)");
    }

    @Override
    public List<DiagnosticInfo> diagnostics(String relPath) {
        // Diagnostics arrive with the language-services phase; none available yet.
        return List.of();
    }

    private static String newOperationId() {
        return "op-" + UUID.randomUUID();
    }
}
