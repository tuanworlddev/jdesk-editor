package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Regenerates the machine-owned section of {@code VERIFICATION.md} between marker comments,
 * leaving hand-written prose untouched. A PASS row can only be emitted for a test that resolves
 * to a cited run whose verifier is clean — an unbacked PASS is a generation error, not a row.
 */
public final class ReportGenerator {

    private static final String BEGIN = "<!-- BEGIN GENERATED:acceptance -->";
    private static final String END = "<!-- END GENERATED:acceptance -->";

    private final Environment environment;
    private final RunVerifier verifier = new RunVerifier();

    public ReportGenerator(Environment environment) {
        this.environment = environment;
    }

    /** Scans cited runs, verifies them, and rewrites the generated block in VERIFICATION.md. */
    public void regenerate() {
        Map<String, Row> rows = collectRows();
        String table = renderTable(rows);
        Path verification = environment.repoRoot().resolve("VERIFICATION.md");
        try {
            String existing = Files.exists(verification)
                    ? Files.readString(verification, StandardCharsets.UTF_8)
                    : defaultScaffold();
            String updated = replaceBlock(existing, table);
            Files.writeString(verification, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing VERIFICATION.md", e);
        }
    }

    private record Row(String testId, String outcome, String runId, String detail) {}

    private Map<String, Row> collectRows() {
        Map<String, Row> rows = new TreeMap<>();
        Path citedDir = environment.repoRoot().resolve("docs/verification/runs");
        if (!Files.isDirectory(citedDir)) {
            return rows;
        }
        try (Stream<Path> runs = Files.list(citedDir)) {
            runs.filter(Files::isDirectory).sorted().forEach(run -> {
                String runId = run.getFileName().toString();
                JsonNode results = CitedRuns.loadResults(environment, runId);
                if (results == null) {
                    return;
                }
                boolean clean = verifier.verify(run).isEmpty();
                for (JsonNode test : results.path("tests")) {
                    String id = test.path("id").asText();
                    String outcome = test.path("outcome").asText();
                    // A PASS survives into the report only when its cited run verifies clean.
                    if ("PASS".equals(outcome) && !clean) {
                        outcome = "FAIL";
                    }
                    String detail = test.path("outcome").asText().equals("BLOCKED")
                            ? test.path("blockedReason").asText("")
                            : test.path("detail").asText("");
                    rows.put(id, new Row(id, outcome, runId, detail));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed scanning cited runs", e);
        }
        return rows;
    }

    private String renderTable(Map<String, Row> rows) {
        List<String> lines = new ArrayList<>();
        lines.add(BEGIN);
        lines.add("");
        lines.add("| Test | Outcome | Evidence | Detail |");
        lines.add("|---|---|---|---|");
        if (rows.isEmpty()) {
            lines.add("| _(no cited runs yet)_ | — | — | — |");
        } else {
            for (Row row : rows.values()) {
                String evidence = row.outcome().equals("BLOCKED")
                        ? "—"
                        : "`docs/verification/runs/" + row.runId() + "`";
                lines.add("| " + row.testId() + " | " + badge(row.outcome()) + " | " + evidence
                        + " | " + escape(row.detail()) + " |");
            }
        }
        lines.add("");
        lines.add(END);
        return String.join("\n", lines);
    }

    private static String badge(String outcome) {
        return switch (outcome.toUpperCase(Locale.ROOT)) {
            case "PASS" -> "✅ PASS";
            case "FAIL" -> "❌ FAIL";
            case "BLOCKED" -> "⛔ BLOCKED";
            default -> outcome;
        };
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    private static String replaceBlock(String document, String table) {
        int begin = document.indexOf(BEGIN);
        int end = document.indexOf(END);
        if (begin >= 0 && end > begin) {
            return document.substring(0, begin) + table + document.substring(end + END.length());
        }
        return document + "\n\n## Acceptance results\n\n" + table + "\n";
    }

    private static String defaultScaffold() {
        return """
                # JDesk Editor — Verification

                This file links every acceptance result to real evidence under
                `docs/verification/runs/<runId>/` (tamper-evident stubs of full runs kept
                out of git under `artifacts/test-runs/`). The table below is generated by
                `evidence-cli report`; do not edit it by hand.

                ## Acceptance results

                """;
    }
}
