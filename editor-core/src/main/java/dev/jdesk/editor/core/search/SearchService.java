package dev.jdesk.editor.core.search;

import dev.jdesk.editor.core.fs.FileTree;
import dev.jdesk.editor.core.fs.PathService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Workspace text search (spec §8.4). Walks the ignore-filtered file tree, skips binary and
 * oversize files, and matches literal text or a regex with optional case sensitivity. Results are
 * streamed to a consumer as they are found and bounded by a max-results cap so a broad query on a
 * huge tree stays responsive.
 */
public final class SearchService {

    /** One match: workspace-relative file, 1-based line, 1-based column, and the full line text. */
    public record Match(String relPath, int line, int column, String lineText) {}

    public record Query(String text, boolean regex, boolean caseSensitive, int maxResults) {}

    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int BINARY_SNIFF_BYTES = 8192;

    private final PathService paths;
    private final FileTree fileTree;

    public SearchService(PathService paths, FileTree fileTree) {
        this.paths = paths;
        this.fileTree = fileTree;
    }

    /** Collects up to {@code query.maxResults()} matches. */
    public List<Match> search(Query query) {
        List<Match> results = new ArrayList<>();
        search(query, results::add);
        return results;
    }

    /** Streams matches to {@code sink} until the tree is exhausted or the cap is reached. */
    public void search(Query query, Consumer<Match> sink) {
        Pattern pattern = compile(query);
        int[] remaining = {query.maxResults() <= 0 ? 10_000 : query.maxResults()};
        walk("", pattern, sink, remaining);
    }

    private void walk(String relDir, Pattern pattern, Consumer<Match> sink, int[] remaining) {
        if (remaining[0] <= 0) {
            return;
        }
        for (FileTree.Entry entry : fileTree.listChildren(relDir)) {
            if (remaining[0] <= 0) {
                return;
            }
            if (entry.directory()) {
                walk(entry.relativePath(), pattern, sink, remaining);
            } else {
                searchFile(entry.relativePath(), pattern, sink, remaining);
            }
        }
    }

    private void searchFile(String relPath, Pattern pattern, Consumer<Match> sink, int[] remaining) {
        Path file = paths.resolveExisting(relPath).absolute();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (looksBinary(bytes)) {
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length && remaining[0] > 0; i++) {
                var matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    sink.accept(new Match(relPath, i + 1, matcher.start() + 1, lines[i]));
                    remaining[0]--;
                }
            }
        } catch (IOException e) {
            // Unreadable file contributes no matches.
        }
    }

    private static Pattern compile(Query query) {
        int flags = query.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
        if (query.regex()) {
            try {
                return Pattern.compile(query.text(), flags);
            } catch (PatternSyntaxException e) {
                return Pattern.compile(Pattern.quote(query.text()), flags);
            }
        }
        return Pattern.compile(Pattern.quote(query.text()), flags);
    }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
