package dev.jdesk.editor.evidence;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only sanctioned way to execute external commands during a verification run. Captures
 * argv, exit code, duration, and redacted stdout/stderr into the run directory, appending one
 * JSON line per command to {@code commands.jsonl} (spec section 25 rule 1).
 */
public final class CommandRecorder {

    /** Result of a recorded command. Stream text is capped in memory; full text is on disk. */
    public record CommandResult(
            int seq,
            List<String> argv,
            int exitCode,
            long durationMs,
            boolean timedOut,
            String stdout,
            String stderr,
            Path stdoutFile,
            Path stderrFile) {

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    private static final int MEMORY_CAP_BYTES = 1024 * 1024;

    private final Path runDir;
    private final Redactor redactor;
    private final AtomicInteger sequence = new AtomicInteger();

    CommandRecorder(Path runDir, Redactor redactor) {
        this.runDir = runDir;
        this.redactor = redactor;
    }

    public CommandResult run(List<String> argv, Path cwd, Map<String, String> extraEnv, Duration timeout) {
        int seq = sequence.incrementAndGet();
        Instant start = Instant.now();
        Path stdoutFile = runDir.resolve("commands").resolve("%03d-stdout.txt".formatted(seq));
        Path stderrFile = runDir.resolve("commands").resolve("%03d-stderr.txt".formatted(seq));
        int exitCode = -1;
        boolean timedOut = false;
        String stdout = "";
        String stderr = "";
        try {
            Files.createDirectories(stdoutFile.getParent());
            ProcessBuilder builder = new ProcessBuilder(argv);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            if (extraEnv != null) {
                builder.environment().putAll(extraEnv);
            }
            builder.redirectOutput(stdoutFile.toFile());
            builder.redirectError(stderrFile.toFile());
            Process process = builder.start();
            if (timeout != null) {
                timedOut = !process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (timedOut) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                } else {
                    exitCode = process.exitValue();
                }
            } else {
                exitCode = process.waitFor();
            }
            stdout = redactFile(stdoutFile);
            stderr = redactFile(stderrFile);
        } catch (IOException e) {
            stderr = "evidence-core failed to launch: " + e;
            writeQuietly(stderrFile, stderr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording command " + argv, e);
        }
        long durationMs = Duration.between(start, Instant.now()).toMillis();

        ObjectNode record = JsonIo.object();
        record.put("seq", seq);
        record.put("tsUtc", start.toString());
        record.set("argv", JsonIo.mapper().valueToTree(argv.stream().map(redactor::redact).toList()));
        record.put("cwd", cwd == null ? "" : cwd.toString());
        record.put("exitCode", exitCode);
        record.put("durationMs", durationMs);
        record.put("timedOut", timedOut);
        record.put("stdout", runDir.relativize(stdoutFile).toString());
        record.put("stderr", runDir.relativize(stderrFile).toString());
        appendLine(runDir.resolve("commands.jsonl"), JsonIo.line(record));

        return new CommandResult(seq, List.copyOf(argv), exitCode, durationMs, timedOut,
                cap(stdout), cap(stderr), stdoutFile, stderrFile);
    }

    /** Redacts the captured stream in place and returns its (redacted) content. */
    private String redactFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String clean = redactor.redact(raw);
        if (!clean.equals(raw)) {
            Files.writeString(file, clean, StandardCharsets.UTF_8);
        }
        return clean;
    }

    private static String cap(String text) {
        return text.length() <= MEMORY_CAP_BYTES ? text : text.substring(0, MEMORY_CAP_BYTES);
    }

    private static void appendLine(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed appending to " + file, e);
        }
    }

    private static void writeQuietly(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort: the failure is already recorded in the command record's stderr field.
        }
    }
}
