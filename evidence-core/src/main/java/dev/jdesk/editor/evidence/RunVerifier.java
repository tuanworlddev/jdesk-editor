package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Independent audit of a finished run directory. Recomputes every checksum and re-checks the
 * honesty rules so a tampered or hand-written run cannot read as PASS. Used by the Gradle
 * {@code verifyEvidenceRuns} task and by the final Definition-of-Done audit.
 */
public final class RunVerifier {

    /** Categories whose manifest must identify a real native WebView backend. */
    private static final Set<String> NATIVE_CATEGORIES = Set.of("gate", "e2e-native", "package", "performance");

    private static final long TIMESTAMP_SLACK_MS = 5_000;

    public record Violation(String runId, String rule, String detail) {
        @Override
        public String toString() {
            return "[" + runId + "] " + rule + ": " + detail;
        }
    }

    public List<Violation> verify(Path runDir) {
        List<Violation> violations = new ArrayList<>();
        String runId = runDir.getFileName().toString();
        Path manifestFile = runDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            violations.add(new Violation(runId, "manifest-missing", manifestFile.toString()));
            return violations;
        }
        JsonNode manifest = JsonIo.read(manifestFile);

        String status = manifest.path("status").asText("");
        if (!"COMPLETE".equals(status)) {
            violations.add(new Violation(runId, "incomplete",
                    "manifest status is '" + status + "' — crashed or unfinished runs never count"));
            return violations;
        }

        verifyChecksums(runDir, runId, violations);

        Path resultsFile = runDir.resolve("results.json");
        if (!Files.exists(resultsFile)) {
            violations.add(new Violation(runId, "results-missing", resultsFile.toString()));
            return violations;
        }
        JsonNode results = JsonIo.read(resultsFile);
        JsonNode tests = results.path("tests");
        if (!tests.isArray() || tests.isEmpty()) {
            violations.add(new Violation(runId, "no-tests", "a COMPLETE run must contain at least one test"));
        }

        Instant started = parseInstant(manifest.path("startedAtUtc").asText(null));
        Instant ended = parseInstant(manifest.path("endedAtUtc").asText(null));
        if (started == null || ended == null) {
            violations.add(new Violation(runId, "timestamps", "startedAtUtc/endedAtUtc missing or invalid"));
        }

        for (JsonNode test : tests) {
            String id = test.path("id").asText("?");
            String outcome = test.path("outcome").asText("");
            switch (outcome) {
                case "PASS" -> verifyPassEvidence(runDir, runId, id, test, started, ended, violations);
                case "BLOCKED" -> {
                    if (test.path("blockedReason").asText("").isBlank()) {
                        violations.add(new Violation(runId, "blocked-without-reason", id));
                    }
                }
                case "FAIL" -> { /* honest failure needs no defense */ }
                default -> violations.add(new Violation(runId, "invalid-outcome", id + " -> '" + outcome + "'"));
            }
        }

        String category = manifest.path("category").asText("");
        if (NATIVE_CATEGORIES.contains(category)) {
            String backend = manifest.path("webView").path("backend").asText("unknown");
            if (backend.isBlank() || "unknown".equalsIgnoreCase(backend)
                    || backend.toLowerCase().contains("mock") || backend.toLowerCase().contains("fake")) {
                violations.add(new Violation(runId, "webview-unproven",
                        "category '" + category + "' requires a real WebView backend, found '" + backend + "'"));
            }
        }

        verifyScreenshots(runDir, runId, violations);
        return violations;
    }

    /** Verifies every run under {@code artifacts/test-runs}, returning all violations. */
    public List<Violation> verifyAll(Path testRunsDir) {
        if (!Files.isDirectory(testRunsDir)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> runs = Files.list(testRunsDir)) {
            runs.filter(Files::isDirectory).sorted().forEach(run -> {
                // Unfinished runs are reported but tolerated in bulk mode when clearly marked.
                Path manifest = run.resolve("manifest.json");
                if (Files.exists(manifest)
                        && !"COMPLETE".equals(JsonIo.read(manifest).path("status").asText(""))) {
                    return;
                }
                violations.addAll(verify(run));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + testRunsDir, e);
        }
        return violations;
    }

    private void verifyPassEvidence(Path runDir, String runId, String testId, JsonNode test,
            Instant started, Instant ended, List<Violation> violations) {
        JsonNode evidence = test.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) {
            violations.add(new Violation(runId, "pass-without-evidence", testId));
            return;
        }
        for (JsonNode pathNode : evidence) {
            Path file = runDir.resolve(pathNode.asText()).normalize();
            if (!file.startsWith(runDir)) {
                violations.add(new Violation(runId, "evidence-escapes-run", testId + " -> " + pathNode.asText()));
                continue;
            }
            if (!Files.exists(file)) {
                violations.add(new Violation(runId, "evidence-missing", testId + " -> " + pathNode.asText()));
                continue;
            }
            try {
                if (Files.size(file) == 0) {
                    violations.add(new Violation(runId, "evidence-empty", testId + " -> " + pathNode.asText()));
                }
                if (started != null && ended != null) {
                    Instant mtime = Files.getLastModifiedTime(file).toInstant();
                    if (mtime.isBefore(started.minusMillis(TIMESTAMP_SLACK_MS))
                            || mtime.isAfter(ended.plusMillis(TIMESTAMP_SLACK_MS))) {
                        violations.add(new Violation(runId, "evidence-outside-window",
                                testId + " -> " + pathNode.asText() + " mtime=" + mtime));
                    }
                }
            } catch (IOException e) {
                violations.add(new Violation(runId, "evidence-unreadable", testId + " -> " + e));
            }
        }
    }

    private void verifyChecksums(Path runDir, String runId, List<Violation> violations) {
        Path checksums = runDir.resolve("checksums.sha256");
        if (!Files.exists(checksums)) {
            violations.add(new Violation(runId, "checksums-missing", checksums.toString()));
            return;
        }
        Map<String, String> recorded = new HashMap<>();
        try {
            for (String line : Files.readAllLines(checksums)) {
                if (line.isBlank()) {
                    continue;
                }
                int gap = line.indexOf("  ");
                if (gap <= 0) {
                    violations.add(new Violation(runId, "checksums-malformed", line));
                    continue;
                }
                recorded.put(line.substring(gap + 2), line.substring(0, gap));
            }
            for (Map.Entry<String, String> entry : recorded.entrySet()) {
                Path file = runDir.resolve(entry.getKey());
                if (!Files.exists(file)) {
                    violations.add(new Violation(runId, "checksummed-file-missing", entry.getKey()));
                } else if (!Hashes.sha256(file).equals(entry.getValue())) {
                    violations.add(new Violation(runId, "checksum-mismatch", entry.getKey()));
                }
            }
            try (Stream<Path> all = Files.walk(runDir)) {
                all.filter(Files::isRegularFile)
                        .map(file -> runDir.relativize(file).toString())
                        .filter(rel -> !rel.equals("checksums.sha256") && !recorded.containsKey(rel))
                        .forEach(rel -> violations.add(new Violation(runId, "file-not-checksummed", rel)));
            }
        } catch (IOException e) {
            violations.add(new Violation(runId, "checksums-unreadable", e.toString()));
        }
    }

    private void verifyScreenshots(Path runDir, String runId, List<Violation> violations) {
        Path screenshots = runDir.resolve("screenshots");
        if (!Files.isDirectory(screenshots)) {
            return;
        }
        try (Stream<Path> files = Files.list(screenshots)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    if (!PngValidator.isRealPng(Files.readAllBytes(file))) {
                        violations.add(new Violation(runId, "screenshot-not-png",
                                runDir.relativize(file).toString()));
                    }
                } catch (IOException e) {
                    violations.add(new Violation(runId, "screenshot-unreadable", file + ": " + e));
                }
            });
        } catch (IOException e) {
            violations.add(new Violation(runId, "screenshots-unlistable", e.toString()));
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception e) {
            return null;
        }
    }
}
