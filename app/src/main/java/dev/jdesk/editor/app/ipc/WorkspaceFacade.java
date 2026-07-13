package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.app.EditorSession;
import dev.jdesk.editor.core.fs.FileTree;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Typed IPC facade for workspace and Explorer operations. Thin: it validates and forwards to the
 * editor core, which holds all logic. Every command requires the {@code editor:core} capability;
 * paths are canonicalized in the core (never here).
 */
public final class WorkspaceFacade {

    private final Supplier<EditorSession> session;
    private final Consumer<EditorSession> onOpen;

    public WorkspaceFacade(Supplier<EditorSession> session, Consumer<EditorSession> onOpen) {
        this.session = session;
        this.onOpen = onOpen;
    }

    @DesktopCommand("workspace.open")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.WorkspaceState> open(
            WorkspaceDtos.OpenWorkspaceRequest request, InvocationContext context) {
        EditorSession opened = new EditorSession(Path.of(request.path()));
        onOpen.accept(opened);
        return CompletableFuture.completedFuture(stateOf(opened));
    }

    @DesktopCommand("workspace.getState")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.WorkspaceState> getState(InvocationContext context) {
        EditorSession current = session.get();
        if (current == null) {
            return CompletableFuture.completedFuture(
                    new WorkspaceDtos.WorkspaceState(false, "", "", List.of()));
        }
        return CompletableFuture.completedFuture(stateOf(current));
    }

    @DesktopCommand("workspace.allFiles")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.FileList> allFiles(InvocationContext context) {
        EditorSession current = require();
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.FileList(current.fileTree().allFilePaths(20_000)));
    }

    @DesktopCommand("workspace.children")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.DirListing> children(
            WorkspaceDtos.ChildrenRequest request, InvocationContext context) {
        EditorSession current = require();
        List<WorkspaceDtos.FsEntry> entries = current.fileTree().listChildren(request.relPath())
                .stream().map(WorkspaceFacade::toDto).toList();
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.DirListing(request.relPath(), entries));
    }

    @DesktopCommand("workspace.createFile")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.FsMutationResult> createFile(
            WorkspaceDtos.CreateFileRequest request, InvocationContext context) {
        EditorSession current = require();
        current.documents().create(request.relPath());
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.FsMutationResult(request.relPath(), true));
    }

    @DesktopCommand("workspace.createFolder")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.FsMutationResult> createFolder(
            WorkspaceDtos.CreateFolderRequest request, InvocationContext context) {
        EditorSession current = require();
        var path = current.paths().resolveForCreate(request.relPath());
        try {
            java.nio.file.Files.createDirectories(path.absolute());
        } catch (java.io.IOException e) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.INTERNAL_ERROR,
                    "Cannot create folder " + request.relPath(), e);
        }
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.FsMutationResult(request.relPath(), true));
    }

    @DesktopCommand("workspace.rename")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.FsMutationResult> rename(
            WorkspaceDtos.RenameRequest request, InvocationContext context) {
        EditorSession current = require();
        var from = current.paths().resolveExisting(request.fromRelPath());
        current.paths().ensureParentDirectories(request.toRelPath());
        var to = current.paths().resolveForCreate(request.toRelPath());
        try {
            java.nio.file.Files.move(from.absolute(), to.absolute());
        } catch (java.io.IOException e) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.INTERNAL_ERROR,
                    "Cannot rename " + request.fromRelPath(), e);
        }
        current.documents().close(from.uri());
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.FsMutationResult(request.toRelPath(), true));
    }

    @DesktopCommand("workspace.delete")
    @RequiresCapability("editor:core")
    public CompletionStage<WorkspaceDtos.FsMutationResult> delete(
            WorkspaceDtos.DeleteRequest request, InvocationContext context) {
        EditorSession current = require();
        var path = current.paths().resolveExisting(request.relPath());
        try {
            if (request.recursive() && java.nio.file.Files.isDirectory(path.absolute())) {
                try (var walk = java.nio.file.Files.walk(path.absolute())) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
                }
            } else {
                java.nio.file.Files.delete(path.absolute());
            }
        } catch (java.io.IOException e) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.INTERNAL_ERROR,
                    "Cannot delete " + request.relPath(), e);
        }
        current.documents().close(path.uri());
        return CompletableFuture.completedFuture(
                new WorkspaceDtos.FsMutationResult(request.relPath(), true));
    }

    private EditorSession require() {
        EditorSession current = session.get();
        if (current == null) {
            throw new dev.jdesk.editor.api.EditorException(
                    dev.jdesk.editor.api.EditorErrorCode.TARGET_NOT_ACTIONABLE, "No workspace is open");
        }
        return current;
    }

    private WorkspaceDtos.WorkspaceState stateOf(EditorSession s) {
        List<WorkspaceDtos.FsEntry> entries = s.fileTree().listRoot().stream()
                .map(WorkspaceFacade::toDto).toList();
        return new WorkspaceDtos.WorkspaceState(true, s.rootName(), s.root().toString(), entries);
    }

    private static WorkspaceDtos.FsEntry toDto(FileTree.Entry e) {
        return new WorkspaceDtos.FsEntry(e.name(), e.relativePath(), e.directory(), e.hasChildren());
    }
}
