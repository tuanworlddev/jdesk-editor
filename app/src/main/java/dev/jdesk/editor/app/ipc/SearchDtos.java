package dev.jdesk.editor.app.ipc;

import java.util.List;

/** Wire DTOs for workspace search. Public records of restricted types only. */
public final class SearchDtos {

    private SearchDtos() {}

    public record Query(String text, boolean regex, boolean caseSensitive, int maxResults) {}

    public record Match(int line, int column, String preview) {}

    public record FileMatches(String relPath, List<Match> matches) {}

    public record Results(List<FileMatches> files, int totalMatches) {}
}
