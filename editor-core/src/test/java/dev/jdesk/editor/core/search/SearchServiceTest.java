package dev.jdesk.editor.core.search;

import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    private SearchService service(Path root) {
        PathService paths = new PathService(root);
        return new SearchService(paths, new FileTree(paths));
    }

    private void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent() == null ? root : file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void findsLiteralMatchesAcrossFiles(@TempDir Path root) throws IOException {
        write(root, "a.txt", "hello world\nsecond line\n");
        write(root, "src/b.txt", "another hello\n");
        SearchService service = service(root);

        List<SearchService.Match> matches = service.search(
                new SearchService.Query("hello", false, false, 100));

        assertThat(matches).extracting(SearchService.Match::relPath)
                .containsExactlyInAnyOrder("a.txt", "src/b.txt");
        assertThat(matches).filteredOn(m -> m.relPath().equals("a.txt")).first()
                .extracting(SearchService.Match::line).isEqualTo(1);
    }

    @Test
    void respectsCaseSensitivity(@TempDir Path root) throws IOException {
        write(root, "a.txt", "Hello\nhello\nHELLO\n");
        SearchService service = service(root);

        assertThat(service.search(new SearchService.Query("hello", false, true, 100))).hasSize(1);
        assertThat(service.search(new SearchService.Query("hello", false, false, 100))).hasSize(3);
    }

    @Test
    void supportsRegex(@TempDir Path root) throws IOException {
        write(root, "code.txt", "int x = 42;\nString y = \"hi\";\nint z = 7;\n");
        SearchService service = service(root);

        List<SearchService.Match> matches = service.search(
                new SearchService.Query("int \\w+ = \\d+", true, false, 100));

        assertThat(matches).hasSize(2);
        assertThat(matches).extracting(SearchService.Match::column).containsOnly(1);
    }

    @Test
    void skipsIgnoredDirectoriesAndBinaryFiles(@TempDir Path root) throws IOException {
        write(root, "src/a.txt", "findme\n");
        write(root, "node_modules/lib.txt", "findme\n");     // ignored dir
        Files.write(root.resolve("bin.dat"), new byte[]{'f', 'i', 0, 'n', 'd'}); // NUL → binary
        SearchService service = service(root);

        List<SearchService.Match> matches = service.search(
                new SearchService.Query("find", false, false, 100));

        assertThat(matches).extracting(SearchService.Match::relPath).containsExactly("src/a.txt");
    }

    @Test
    void honoursMaxResultsCap(@TempDir Path root) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            big.append("match here ").append(i).append('\n');
        }
        write(root, "many.txt", big.toString());
        SearchService service = service(root);

        assertThat(service.search(new SearchService.Query("match", false, false, 10))).hasSize(10);
    }
}
