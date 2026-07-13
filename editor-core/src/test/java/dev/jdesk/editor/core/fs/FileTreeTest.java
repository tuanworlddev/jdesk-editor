package dev.jdesk.editor.core.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTreeTest {

    @Test
    void listsChildrenFoldersFirstThenFilesAlphabetically(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("README.md"), "x");
        Files.writeString(root.resolve("build.gradle.kts"), "x");
        FileTree tree = new FileTree(new PathService(root));

        List<FileTree.Entry> entries = tree.listRoot();

        assertThat(entries).extracting(FileTree.Entry::name)
                .containsExactly("docs", "src", "build.gradle.kts", "README.md");
        assertThat(entries.get(0).directory()).isTrue();
    }

    @Test
    void hidesIgnoredDirectoriesByDefault(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("src"));
        FileTree tree = new FileTree(new PathService(root));

        assertThat(tree.listRoot()).extracting(FileTree.Entry::name).containsExactly("src");
    }

    @Test
    void reportsHasChildrenForNonEmptyDirectories(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("full"));
        Files.writeString(root.resolve("full/a.txt"), "x");
        Files.createDirectories(root.resolve("empty"));
        FileTree tree = new FileTree(new PathService(root));

        List<FileTree.Entry> entries = tree.listRoot();
        assertThat(entries).filteredOn(e -> e.name().equals("full")).first()
                .extracting(FileTree.Entry::hasChildren).isEqualTo(true);
        assertThat(entries).filteredOn(e -> e.name().equals("empty")).first()
                .extracting(FileTree.Entry::hasChildren).isEqualTo(false);
    }

    @Test
    void listsNestedChildrenLazily(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(root.resolve("a/b/deep.txt"), "x");
        FileTree tree = new FileTree(new PathService(root));

        List<FileTree.Entry> children = tree.listChildren("a/b");
        assertThat(children).extracting(FileTree.Entry::relativePath).containsExactly("a/b/deep.txt");
    }

    @Test
    void countsVisibleFilesIgnoringHiddenDirs(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/a.txt"), "x");
        Files.writeString(root.resolve("src/b.txt"), "x");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/ignored.js"), "x");

        assertThat(new FileTree(new PathService(root)).countVisibleFiles()).isEqualTo(2);
    }
}
