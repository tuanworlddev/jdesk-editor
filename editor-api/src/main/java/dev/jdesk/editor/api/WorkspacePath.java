package dev.jdesk.editor.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A path proven to lie inside a workspace root: both its canonical absolute {@link Path} and its
 * normalized workspace-relative string. This is the <em>only</em> path type the document store,
 * atomic saver, search, git, terminal, and MCP tools accept — everything upstream must go through
 * {@code PathService} to obtain one, which is the single canonicalization/containment choke point
 * (spec §21). Construct via {@code PathService}; the public constructor exists for that service and
 * for tests, and trusts its inputs.
 *
 * @param absolute canonical, absolute, symlink-resolved path on disk (parent-resolved for
 *        not-yet-existing files)
 * @param relative forward-slash workspace-relative path, no leading slash, no {@code ..}
 */
public record WorkspacePath(Path absolute, String relative) {

    public WorkspacePath {
        Objects.requireNonNull(absolute, "absolute");
        Objects.requireNonNull(relative, "relative");
        if (!absolute.isAbsolute()) {
            throw new IllegalArgumentException("WorkspacePath.absolute must be absolute: " + absolute);
        }
        if (relative.startsWith("/") || relative.contains("..")) {
            throw new IllegalArgumentException("Illegal workspace-relative path: " + relative);
        }
    }

    /** The editor URI form used on the wire: {@code file:///<absolute>}. */
    public String uri() {
        return absolute.toUri().toString();
    }

    @Override
    public String toString() {
        return relative;
    }
}
