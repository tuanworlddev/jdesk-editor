package dev.jdesk.editor.app;

import dev.jdesk.editor.core.doc.AtomicSaver;
import dev.jdesk.editor.core.doc.DocumentStore;
import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;

import java.nio.file.Path;

/**
 * Everything scoped to the currently-open workspace: the path choke point, the lazy file tree, and
 * the authoritative document store. A new session is created each time a folder is opened, so
 * closing or switching a workspace cleanly drops all its documents and leases.
 */
public final class EditorSession {

    private final Path root;
    private final PathService paths;
    private final FileTree fileTree;
    private final DocumentStore documents;

    public EditorSession(Path root) {
        this.root = root;
        this.paths = new PathService(root);
        this.fileTree = new FileTree(paths);
        this.documents = new DocumentStore(paths, new AtomicSaver(), System::currentTimeMillis);
    }

    public Path root() {
        return paths.root();
    }

    public String rootName() {
        Path name = paths.root().getFileName();
        return name == null ? paths.root().toString() : name.toString();
    }

    public PathService paths() {
        return paths;
    }

    public FileTree fileTree() {
        return fileTree;
    }

    public DocumentStore documents() {
        return documents;
    }
}
