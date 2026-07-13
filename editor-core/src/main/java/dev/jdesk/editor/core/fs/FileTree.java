package dev.jdesk.editor.core.fs;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.WorkspacePath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Lazy, workspace-scoped directory listing for the Explorer (spec §8.3). Children are loaded on
 * demand (never a full recursive walk), configurable build/dependency/VCS directories are hidden by
 * default, and every entry carries a stable workspace-relative id. All paths pass through
 * {@link PathService}, so a listing can never escape the workspace.
 */
public final class FileTree {

    /**
     * One Explorer entry.
     *
     * @param name display name (file/dir name only)
     * @param relativePath workspace-relative path, the stable semantic id suffix
     * @param directory true for a folder
     * @param hasChildren for a folder, whether it has any visible children (drives the twisty)
     */
    public record Entry(String name, String relativePath, boolean directory, boolean hasChildren) {}

    private static final Set<String> DEFAULT_IGNORED = Set.of(
            ".git", ".hg", ".svn", "node_modules", "build", "dist", "out", "target",
            ".gradle", ".idea", ".next", "__pycache__", ".DS_Store");

    private final PathService paths;
    private final Set<String> ignored;

    public FileTree(PathService paths) {
        this(paths, DEFAULT_IGNORED);
    }

    public FileTree(PathService paths, Set<String> ignored) {
        this.paths = paths;
        this.ignored = Set.copyOf(ignored);
    }

    /** Lists the immediate children of the workspace root. */
    public List<Entry> listRoot() {
        return listChildren("");
    }

    /**
     * Lists the immediate children of a directory (empty string = root). Folders sort before files,
     * then case-insensitive by name — the order the Explorer renders.
     */
    public List<Entry> listChildren(String relativeDir) {
        Path dir = relativeDir.isEmpty() ? paths.root() : paths.resolveExisting(relativeDir).absolute();
        if (!Files.isDirectory(dir)) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_ACTIONABLE,
                    "Not a directory: " + relativeDir);
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                String name = child.getFileName().toString();
                if (ignored.contains(name)) {
                    continue;
                }
                boolean directory = Files.isDirectory(child);
                String rel = paths.root().relativize(child).toString().replace('\\', '/');
                entries.add(new Entry(name, rel, directory, directory && hasVisibleChildren(child)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed listing " + relativeDir, e);
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(e -> e.name().toLowerCase()));
        return entries;
    }

    private boolean hasVisibleChildren(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(c -> !ignored.contains(c.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    /** Counts all visible files under the workspace (used by performance/interactivity checks). */
    public long countVisibleFiles() {
        return countVisibleFiles(paths.root());
    }

    private long countVisibleFiles(Path dir) {
        long count = 0;
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (ignored.contains(child.getFileName().toString())) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    count += countVisibleFiles(child);
                } else {
                    count++;
                }
            }
        } catch (IOException e) {
            // A directory we cannot read contributes nothing.
        }
        return count;
    }
}
