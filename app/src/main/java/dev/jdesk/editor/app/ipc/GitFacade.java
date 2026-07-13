package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.app.EditorSession;
import dev.jdesk.editor.git.GitService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Typed IPC facade for read-only Git integration (spec §19), backed by the session's GitService. */
public final class GitFacade {

    private final Supplier<EditorSession> session;

    public GitFacade(Supplier<EditorSession> session) {
        this.session = session;
    }

    @DesktopCommand("git.status")
    @RequiresCapability("editor:core")
    public CompletionStage<GitDtos.Status> status(InvocationContext ctx) {
        EditorSession current = session.get();
        if (current == null || !current.git().available()) {
            return CompletableFuture.completedFuture(
                    new GitDtos.Status(false, "", 0, 0, List.of()));
        }
        GitService.Status status = current.git().status();
        List<GitDtos.FileStatus> files = status.files().stream()
                .map(f -> new GitDtos.FileStatus(f.relPath(), String.valueOf(f.index()),
                        String.valueOf(f.worktree()), f.untracked()))
                .toList();
        return CompletableFuture.completedFuture(new GitDtos.Status(
                true, status.branch(), status.ahead(), status.behind(), files));
    }

    @DesktopCommand("git.diff")
    @RequiresCapability("editor:core")
    public CompletionStage<GitDtos.Diff> diff(GitDtos.DiffRequest request, InvocationContext ctx) {
        EditorSession current = session.get();
        if (current == null || !current.git().available()) {
            return CompletableFuture.completedFuture(new GitDtos.Diff(request.relPath(), "", ""));
        }
        // Original = committed content (HEAD); modified = the live editor buffer if open, else disk.
        String original = current.git().showBlob("HEAD", request.relPath());
        String modified;
        var open = current.documents().find(
                current.paths().resolveExisting(request.relPath()).uri());
        if (open.isPresent()) {
            modified = open.get().content();
        } else {
            try {
                modified = java.nio.file.Files.readString(
                        current.paths().resolveExisting(request.relPath()).absolute());
            } catch (Exception e) {
                modified = "";
            }
        }
        return CompletableFuture.completedFuture(new GitDtos.Diff(request.relPath(), original, modified));
    }
}
