package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One verification run: a directory under {@code artifacts/test-runs/<runId>/} following the
 * spec section 25 layout. The manifest is written {@code INCOMPLETE} immediately so a crashed
 * run can never read as success; {@link #finish()} flips it exactly once.
 *
 * <p>Honesty rules enforced here at write time (and re-checked by {@link RunVerifier}):
 * a PASS requires evidence files that exist inside this run; BLOCKED requires a reason; the
 * JDesk framework checkout must match the pinned SHA and be clean, otherwise the run refuses
 * to start (provenance would be meaningless).
 */
public final class TestRun implements AutoCloseable {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Environment environment;
    private final Path dir;
    private final String runId;
    private final String category;
    private final String suite;
    private final Instant startedAt;
    private final Redactor redactor;
    private final CommandRecorder commands;
    private final ObjectNode manifest;
    private final List<TestResult> results = new ArrayList<>();
    private boolean finished;

    private TestRun(Environment environment, String category, String suite) {
        this.environment = environment;
        this.category = category;
        this.suite = suite;
        this.startedAt = Instant.now();
        this.runId = RUN_ID_FORMAT.format(startedAt) + "-" + randomSuffix();
        this.dir = environment.artifactsDir().resolve("test-runs").resolve(runId);
        this.redactor = new Redactor();
        try {
            Files.createDirectories(dir.resolve("screenshots"));
            Files.createDirectories(dir.resolve("commands"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create run directory " + dir, e);
        }
        this.commands = new CommandRecorder(dir, redactor);
        this.manifest = JsonIo.object();
        manifest.put("schemaVersion", 1);
        manifest.put("runId", runId);
        manifest.put("category", category);
        manifest.put("suite", suite);
        manifest.put("status", "INCOMPLETE");
        manifest.put("startedAtUtc", startedAt.toString());
        captureEnvironment();
        writeManifest();
    }

    /**
     * Starts a run after verifying framework provenance. Fails (throws) when the JDesk
     * checkout is not exactly at the pinned SHA or has local modifications.
     */
    public static TestRun start(Environment environment, String category, String suite) {
        TestRun run = new TestRun(environment, category, suite);
        run.enforceJdeskPin();
        return run;
    }

    public Path dir() {
        return dir;
    }

    public String runId() {
        return runId;
    }

    public CommandRecorder commands() {
        return commands;
    }

    public Redactor redactor() {
        return redactor;
    }

    /** Resolves a run-relative evidence file path (creating parent directories). */
    public Path file(String relative) {
        Path resolved = dir.resolve(relative).normalize();
        if (!resolved.startsWith(dir)) {
            throw new IllegalArgumentException("Evidence path escapes run dir: " + relative);
        }
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create parent for " + resolved, e);
        }
        return resolved;
    }

    /** Records a test result, enforcing evidence rules at write time. */
    public synchronized void addResult(TestResult result) {
        ensureOpen();
        if (result.outcome() == RunOutcome.PASS) {
            if (result.evidence().isEmpty()) {
                throw new IllegalArgumentException(
                        "PASS without evidence is forbidden (spec 25 rule 6): " + result.id());
            }
            for (String evidence : result.evidence()) {
                Path path = dir.resolve(evidence).normalize();
                if (!path.startsWith(dir) || !Files.exists(path)) {
                    throw new IllegalArgumentException("PASS evidence missing for " + result.id()
                            + ": " + evidence);
                }
                try {
                    if (Files.size(path) == 0) {
                        throw new IllegalArgumentException("PASS evidence empty for " + result.id()
                                + ": " + evidence);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot stat evidence " + path, e);
                }
            }
        }
        results.add(result);
    }

    /** Convenience: records the outcome of a recorded command as a test result. */
    public void addCommandResult(String testId, CommandRecorder.CommandResult command, String detail) {
        // commands.jsonl always carries the argv/exit/duration record; stream files are cited
        // only when non-empty (a successful command legitimately leaves stderr empty).
        List<String> evidence = new ArrayList<>();
        evidence.add("commands.jsonl");
        addIfNonEmpty(evidence, command.stdoutFile());
        addIfNonEmpty(evidence, command.stderrFile());
        if (command.succeeded()) {
            addResult(TestResult.pass(testId, command.durationMs(), evidence, detail));
        } else {
            addResult(TestResult.fail(testId, command.durationMs(), evidence,
                    detail + " (exit=" + command.exitCode() + ", timedOut=" + command.timedOut() + ")"));
        }
    }

    private void addIfNonEmpty(List<String> evidence, Path file) {
        try {
            if (Files.exists(file) && Files.size(file) > 0) {
                evidence.add(dir.relativize(file).toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot stat " + file, e);
        }
    }

    /**
     * Ingests a {@code gate-results.json} the app wrote into this run directory (schema:
     * {@code {tests:[{id,outcome,detail,evidence[]}], appInfo:{backend,...}}}). Each test becomes a
     * result; the app's own evidence files (already inside the run dir) back the PASS rows.
     */
    public synchronized void ingestAppResults() {
        Path resultsFile = dir.resolve("gate-results.json");
        if (!Files.exists(resultsFile)) {
            addResult(TestResult.fail("APP-RESULTS", 0, List.of("commands.jsonl"),
                    "app produced no gate-results.json"));
            return;
        }
        var node = JsonIo.read(resultsFile);
        for (var test : node.path("tests")) {
            String id = test.path("id").asText();
            String outcome = test.path("outcome").asText();
            String detail = test.path("detail").asText("");
            List<String> evidence = new ArrayList<>();
            test.path("evidence").forEach(e -> evidence.add(e.asText()));
            if ("PASS".equals(outcome)) {
                addResult(TestResult.pass(id, 0, evidence, detail));
            } else {
                addResult(TestResult.fail(id, 0, evidence, detail));
            }
        }
    }

    /** Captures tool versions through real {@code --version} invocations into the manifest. */
    public void captureToolVersions(Map<String, List<String>> probes) {
        ObjectNode versions = manifest.withObject("/versions");
        for (Map.Entry<String, List<String>> probe : probes.entrySet()) {
            CommandRecorder.CommandResult result =
                    commands.run(probe.getValue(), environment.repoRoot(), Map.of(), Duration.ofSeconds(60));
            String value = result.succeeded()
                    ? firstLine(result.stdout().isBlank() ? result.stderr() : result.stdout())
                    : "UNAVAILABLE";
            versions.put(probe.getKey(), value);
        }
        writeManifest();
    }

    /** Merges app-written diagnostics (WebView backend/version) when present. */
    public void mergeAppInfo() {
        Path appInfo = dir.resolve("app-info.json");
        if (Files.exists(appInfo)) {
            manifest.set("webView", JsonIo.read(appInfo));
            writeManifest();
        }
    }

    /** Finishes the run: results.json, manifest COMPLETE, checksums. Call exactly once. */
    public synchronized RunOutcome finish() {
        ensureOpen();
        finished = true;
        RunOutcome overall = overallOutcome();

        ObjectNode resultsJson = JsonIo.object();
        resultsJson.put("schemaVersion", 1);
        resultsJson.put("runId", runId);
        resultsJson.put("suite", suite);
        ArrayNode tests = resultsJson.putArray("tests");
        int pass = 0;
        int fail = 0;
        int blocked = 0;
        for (TestResult result : results) {
            ObjectNode node = tests.addObject();
            node.put("id", result.id());
            node.put("outcome", result.outcome().name());
            node.put("durationMs", result.durationMs());
            node.set("evidence", JsonIo.mapper().valueToTree(result.evidence()));
            if (result.detail() != null) {
                node.put("detail", redactor.redact(result.detail()));
            }
            if (result.blockedReason() != null) {
                node.put("blockedReason", redactor.redact(result.blockedReason()));
            }
            if (result.dependencyProbeSeq() != null) {
                node.put("dependencyProbeSeq", result.dependencyProbeSeq());
            }
            switch (result.outcome()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case BLOCKED -> blocked++;
            }
        }
        ObjectNode summary = resultsJson.putObject("summary");
        summary.put("pass", pass);
        summary.put("fail", fail);
        summary.put("blocked", blocked);
        resultsJson.put("overall", overall.name());
        JsonIo.write(dir.resolve("results.json"), resultsJson);

        Instant endedAt = Instant.now();
        manifest.put("endedAtUtc", endedAt.toString());
        manifest.put("status", "COMPLETE");
        manifest.put("outcome", overall.name());
        ArrayNode testIds = manifest.putArray("testIds");
        results.forEach(result -> testIds.add(result.id()));
        writeManifest();
        writeChecksums();
        return overall;
    }

    @Override
    public void close() {
        // Intentionally does NOT auto-finish: a run abandoned by a crash must stay INCOMPLETE.
    }

    private RunOutcome overallOutcome() {
        if (results.isEmpty()) {
            return RunOutcome.FAIL;
        }
        boolean anyFail = results.stream().anyMatch(result -> result.outcome() == RunOutcome.FAIL);
        if (anyFail) {
            return RunOutcome.FAIL;
        }
        boolean anyBlocked = results.stream().anyMatch(result -> result.outcome() == RunOutcome.BLOCKED);
        return anyBlocked ? RunOutcome.BLOCKED : RunOutcome.PASS;
    }

    private void captureEnvironment() {
        ObjectNode os = manifest.putObject("os");
        os.put("name", System.getProperty("os.name"));
        os.put("version", System.getProperty("os.version"));
        os.put("arch", System.getProperty("os.arch"));
        manifest.withObject("/versions").put("java", Runtime.version().toString());

        manifest.put("appCommit", gitOutput(environment.repoRoot(), "rev-parse", "HEAD"));
        manifest.put("appDirty", !gitOutput(environment.repoRoot(), "status", "--porcelain").isBlank());
        manifest.put("jdeskSha", gitOutput(environment.jdeskSource(), "rev-parse", "HEAD"));
        manifest.put("jdeskDirty", !gitOutput(environment.jdeskSource(), "status", "--porcelain").isBlank());
        manifest.put("jdeskPinnedSha", environment.jdeskPinnedSha());
    }

    private void enforceJdeskPin() {
        String actual = manifest.path("jdeskSha").asText();
        boolean dirty = manifest.path("jdeskDirty").asBoolean();
        if (!actual.equals(environment.jdeskPinnedSha()) || dirty) {
            String message = "JDesk checkout provenance failure: expected clean "
                    + environment.jdeskPinnedSha() + " but found " + actual + (dirty ? " (dirty)" : "")
                    + " at " + environment.jdeskSource()
                    + ". Update jdeskPinnedSha in gradle.properties intentionally or restore the checkout.";
            manifest.put("status", "INCOMPLETE");
            manifest.put("provenanceFailure", message);
            writeManifest();
            throw new IllegalStateException(message);
        }
    }

    private String gitOutput(Path repo, String... args) {
        List<String> argv = new ArrayList<>(List.of("git", "-C", repo.toString()));
        argv.addAll(List.of(args));
        CommandRecorder.CommandResult result = commands.run(argv, environment.repoRoot(), Map.of(),
                Duration.ofSeconds(30));
        return result.succeeded() ? result.stdout().trim() : "UNAVAILABLE";
    }

    private void writeManifest() {
        JsonIo.write(dir.resolve("manifest.json"), manifest);
    }

    private void writeChecksums() {
        Map<String, String> hashes = new TreeMap<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().equals("checksums.sha256")) {
                        hashes.put(dir.relativize(file).toString(), Hashes.sha256(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                body.append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
            }
            Files.writeString(dir.resolve("checksums.sha256"), body.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing checksums for " + dir, e);
        }
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalStateException("Run already finished: " + runId);
        }
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline >= 0 ? text.substring(0, newline) : text).trim();
    }
}
