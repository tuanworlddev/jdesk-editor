package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies the tamper-evident stub of a full run (manifest, results, checksums only — no logs or
 * screenshots) into {@code docs/verification/runs/<runId>/} so VERIFICATION.md can cite it from
 * a clean checkout. Full runs stay gitignored under {@code artifacts/test-runs/}.
 */
public final class CitedRuns {

    private static final String[] STUB_FILES = {"manifest.json", "results.json", "checksums.sha256"};

    private CitedRuns() {}

    public static Path cite(Environment environment, String runId) {
        Path source = environment.artifactsDir().resolve("test-runs").resolve(runId);
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("No such run to cite: " + runId + " (" + source + ")");
        }
        Path target = environment.repoRoot().resolve("docs/verification/runs").resolve(runId);
        Redactor redactor = new Redactor();
        try {
            Files.createDirectories(target);
            for (String name : STUB_FILES) {
                Path from = source.resolve(name);
                if (Files.exists(from)) {
                    String content = redactor.redact(Files.readString(from, StandardCharsets.UTF_8));
                    Files.writeString(target.resolve(name), content, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed citing run " + runId, e);
        }
        return target;
    }

    /** Loads a cited run's outcome for report generation, or null when absent. */
    public static JsonNode loadResults(Environment environment, String runId) {
        Path results = environment.repoRoot().resolve("docs/verification/runs").resolve(runId).resolve("results.json");
        return Files.exists(results) ? JsonIo.read(results) : null;
    }
}
