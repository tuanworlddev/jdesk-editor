package dev.jdesk.editor.app.ipc;

import java.util.List;

/** Wire DTOs for workspace and Explorer operations. Public records of restricted types only. */
public final class WorkspaceDtos {

    private WorkspaceDtos() {}

    /** One Explorer node; {@code relPath} is the stable semantic id suffix. */
    public record FsEntry(String name, String relPath, boolean directory, boolean hasChildren) {}

    public record OpenWorkspaceRequest(String path) {}

    public record WorkspaceState(boolean open, String rootName, String rootPath,
            List<FsEntry> rootEntries) {}

    public record ChildrenRequest(String relPath) {}

    public record DirListing(String relPath, List<FsEntry> entries) {}

    public record CreateFileRequest(String relPath) {}

    public record CreateFolderRequest(String relPath) {}

    public record FsMutationResult(String relPath, boolean ok) {}
}
