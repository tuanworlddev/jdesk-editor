package dev.jdesk.editor.app.ipc;

import java.util.List;

/** Wire DTOs for Git status and diff. Public records of restricted types only. */
public final class GitDtos {

    private GitDtos() {}

    public record FileStatus(String relPath, String index, String worktree, boolean untracked) {}

    public record Status(boolean available, String branch, int ahead, int behind,
            List<FileStatus> files) {}

    public record DiffRequest(String relPath) {}

    public record Diff(String relPath, String original, String modified) {}
}
