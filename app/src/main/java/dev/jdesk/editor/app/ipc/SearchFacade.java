package dev.jdesk.editor.app.ipc;

import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.RequiresCapability;
import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;
import dev.jdesk.editor.app.EditorSession;
import dev.jdesk.editor.core.search.SearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Typed IPC facade for workspace search (spec §8.4), backed by the session's SearchService. */
public final class SearchFacade {

    private final Supplier<EditorSession> session;

    public SearchFacade(Supplier<EditorSession> session) {
        this.session = session;
    }

    @DesktopCommand("search.run")
    @RequiresCapability("editor:core")
    public CompletionStage<SearchDtos.Results> run(SearchDtos.Query request, InvocationContext ctx) {
        EditorSession current = session.get();
        if (current == null) {
            throw new EditorException(EditorErrorCode.TARGET_NOT_ACTIONABLE, "No workspace is open");
        }
        if (request.text() == null || request.text().isBlank()) {
            return CompletableFuture.completedFuture(new SearchDtos.Results(List.of(), 0));
        }
        int max = request.maxResults() <= 0 ? 2000 : request.maxResults();
        List<SearchService.Match> matches = current.search().search(
                new SearchService.Query(request.text(), request.regex(), request.caseSensitive(), max));

        // Group matches by file, preserving discovery order.
        Map<String, List<SearchDtos.Match>> byFile = new LinkedHashMap<>();
        for (SearchService.Match m : matches) {
            byFile.computeIfAbsent(m.relPath(), k -> new ArrayList<>())
                    .add(new SearchDtos.Match(m.line(), m.column(), truncate(m.lineText())));
        }
        List<SearchDtos.FileMatches> files = new ArrayList<>();
        byFile.forEach((relPath, ms) -> files.add(new SearchDtos.FileMatches(relPath, ms)));
        return CompletableFuture.completedFuture(new SearchDtos.Results(files, matches.size()));
    }

    private static String truncate(String line) {
        String trimmed = line.stripLeading();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
