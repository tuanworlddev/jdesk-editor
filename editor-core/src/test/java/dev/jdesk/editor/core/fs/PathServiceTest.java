package dev.jdesk.editor.core.fs;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.api.WorkspacePath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The path security choke point (spec §21, §24.1). If any of these regress, an agent or a stale
 * path could read or overwrite files outside the workspace, so they are treated as critical.
 */
class PathServiceTest {

    @Test
    void resolvesAContainedExistingFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        PathService service = new PathService(root);

        WorkspacePath resolved = service.resolveExisting("src/App.java");

        assertThat(resolved.relative()).isEqualTo("src/App.java");
        assertThat(resolved.absolute()).isEqualTo(root.toRealPath().resolve("src/App.java"));
    }

    @Test
    void rejectsDotDotTraversal(@TempDir Path root) {
        PathService service = new PathService(root);
        assertThatThrownBy(() -> service.resolveExisting("../etc/passwd"))
                .isInstanceOf(EditorException.class)
                .extracting(e -> ((EditorException) e).code())
                .isEqualTo(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void rejectsEmbeddedTraversal(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        PathService service = new PathService(root);
        assertThatThrownBy(() -> service.resolveForCreate("a/b/../../../outside.txt"))
                .isInstanceOf(EditorException.class)
                .extracting(e -> ((EditorException) e).code())
                .isEqualTo(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace(@TempDir Path root, @TempDir Path other) throws IOException {
        Files.writeString(other.resolve("secret.txt"), "secret");
        PathService service = new PathService(root);
        assertThatThrownBy(() -> service.resolveExisting(other.resolve("secret.txt").toString()))
                .isInstanceOf(EditorException.class)
                .extracting(e -> ((EditorException) e).code())
                .isEqualTo(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void rejectsSymlinkEscapeToExistingFile(@TempDir Path root, @TempDir Path other) throws IOException {
        Path secret = Files.writeString(other.resolve("secret.txt"), "secret");
        Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // symlinks unsupported on this FS — skip
        }
        PathService service = new PathService(root);
        EditorException ex = catchThrowableOfType(EditorException.class,
                () -> service.resolveExisting("link.txt"));
        assertThat(ex.code()).isEqualTo(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void rejectsCreatingThroughASymlinkedDirectoryEscape(@TempDir Path root, @TempDir Path other)
            throws IOException {
        Path link = root.resolve("escape");
        try {
            Files.createSymbolicLink(link, other);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        PathService service = new PathService(root);
        // Parent 'escape' resolves (via symlink) to 'other', which is outside the workspace.
        assertThatThrownBy(() -> service.resolveForCreate("escape/new.txt"))
                .isInstanceOf(EditorException.class)
                .extracting(e -> ((EditorException) e).code())
                .isEqualTo(EditorErrorCode.WORKSPACE_BOUNDARY_VIOLATION);
    }

    @Test
    void allowsCreatingANewFileInAContainedDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        PathService service = new PathService(root);

        WorkspacePath created = service.resolveForCreate("src/New.java");

        assertThat(created.relative()).isEqualTo("src/New.java");
        assertThat(created.absolute().getParent()).isEqualTo(root.toRealPath().resolve("src"));
    }

    @Test
    void allowsSymlinkThatStaysInsideWorkspace(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("real"));
        Path target = Files.writeString(root.resolve("real/file.txt"), "hi");
        Path link = root.resolve("alias.txt");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        PathService service = new PathService(root);

        WorkspacePath resolved = service.resolveExisting("alias.txt");
        // Resolves to the real in-workspace target.
        assertThat(resolved.relative()).isEqualTo("real/file.txt");
    }

    @Test
    void rejectsNonexistentWorkspaceRoot(@TempDir Path root) {
        assertThatThrownBy(() -> new PathService(root.resolve("does-not-exist")))
                .isInstanceOf(EditorException.class);
    }

    @Test
    void errorMessagesDoNotLeakAbsolutePaths(@TempDir Path root, @TempDir Path other) {
        PathService service = new PathService(root);
        EditorException ex = catchThrowableOfType(EditorException.class,
                () -> service.resolveExisting(other.resolve("secret.txt").toString()));
        assertThat(ex.getMessage()).doesNotContain(other.toString());
        assertThat(ex.getMessage()).doesNotContain(root.toString());
    }
}
