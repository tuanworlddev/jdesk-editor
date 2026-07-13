package dev.jdesk.editor.core.fs;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.WorkspacePath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * The single path canonicalization and containment choke point (spec §21). Every path that
 * reaches the document store, atomic saver, search, git, terminal, or an MCP tool must first be
 * turned into a {@link WorkspacePath} here. Traversal ({@code ..}) and symlink escape are rejected;
 * a symlink is never followed to a target outside the workspace.
 *
 * <p>The workspace root is resolved to its real path once at construction; all containment checks
 * are made against that canonical root, so a symlinked root is handled consistently.
 */
public final class PathService {

    private final Path canonicalRoot;

    public PathService(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        try {
            Path real = workspaceRoot.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                        "Workspace root is not a directory: " + workspaceRoot);
            }
            this.canonicalRoot = real;
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                    "Workspace root does not exist: " + workspaceRoot, e);
        }
    }

    public Path root() {
        return canonicalRoot;
    }

    /**
     * Resolves a path that must already exist on disk. The real (symlink-resolved) path must lie
     * inside the workspace; a symlink pointing outside is rejected.
     */
    public WorkspacePath resolveExisting(String relativeOrAbsolute) {
        Path candidate = rawResolve(relativeOrAbsolute);
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_FOUND,
                    "Path does not exist: " + safeDisplay(relativeOrAbsolute), e);
        }
        return toContainedWorkspacePath(real, relativeOrAbsolute);
    }

    /**
     * Resolves a path for a file or directory to be created. The parent must exist and be inside
     * the workspace (resolved through symlinks); the leaf is a plain normalized name. If the target
     * itself already exists as a symlink, its real location must also be contained.
     */
    public WorkspacePath resolveForCreate(String relative) {
        Path candidate = rawResolve(relative);
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new EditorException(EditorErrorCode.INVALID_ARGUMENT,
                    "Path has no parent: " + safeDisplay(relative));
        }
        Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_FOUND,
                    "Parent directory does not exist: " + safeDisplay(relative), e);
        }
        if (!isContained(realParent)) {
            throw new EditorException(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION,
                    "Parent escapes the workspace: " + safeDisplay(relative));
        }
        Path realTarget = realParent.resolve(candidate.getFileName());
        // If the target exists as a symlink, ensure it does not point outside.
        if (Files.isSymbolicLink(realTarget)) {
            try {
                Path resolved = realTarget.toRealPath();
                if (!isContained(resolved)) {
                    throw new EditorException(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION,
                            "Target symlink escapes the workspace: " + safeDisplay(relative));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return toContainedWorkspacePath(realTarget, relative);
    }

    /**
     * Creates any missing ancestor directories of a workspace-relative path, top-down, validating
     * containment at each step (so an agent can create {@code src/new/File.txt} in one call without
     * a symlink or traversal escaping the workspace).
     */
    public void ensureParentDirectories(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        String[] parts = normalized.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(parts[i]);
            WorkspacePath dir = resolveForCreate(prefix.toString());
            try {
                Files.createDirectories(dir.absolute());
            } catch (IOException e) {
                throw new EditorException(EditorErrorCode.INTERNAL_ERROR,
                        "Cannot create directory " + dir.relative(), e);
            }
        }
    }

    /** True if the given already-real path lies inside the workspace root. */
    public boolean isContained(Path realPath) {
        Path normalized = realPath.normalize();
        // Case-insensitive filesystems (APFS default, NTFS): compare case-folded.
        if (normalized.equals(canonicalRoot) || normalized.startsWith(canonicalRoot)) {
            return true;
        }
        return normalized.toString().toLowerCase(Locale.ROOT)
                .startsWith(canonicalRoot + Path.of("").getFileSystem().getSeparator());
    }

    // ---- internals ----

    private Path rawResolve(String input) {
        Objects.requireNonNull(input, "path");
        if (input.isBlank()) {
            throw new EditorException(EditorErrorCode.INVALID_ARGUMENT, "Empty path");
        }
        // Reject explicit traversal segments up front (defense in depth; real-path check follows).
        for (String segment : input.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new EditorException(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION,
                        "Path traversal is not allowed: " + safeDisplay(input));
            }
        }
        Path raw = Path.of(input);
        Path resolved = raw.isAbsolute() ? raw : canonicalRoot.resolve(raw);
        return resolved.normalize();
    }

    private WorkspacePath toContainedWorkspacePath(Path realPath, String original) {
        if (!isContained(realPath)) {
            throw new EditorException(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION,
                    "Path escapes the workspace: " + safeDisplay(original));
        }
        String relative = canonicalRoot.relativize(realPath).toString().replace('\\', '/');
        return new WorkspacePath(realPath, relative);
    }

    /** Never echo an absolute filesystem path back to a caller (spec: no unrelated absolute paths). */
    private static String safeDisplay(String input) {
        Path p = Path.of(input);
        return p.getFileName() == null ? "<root>" : p.getFileName().toString();
    }
}
