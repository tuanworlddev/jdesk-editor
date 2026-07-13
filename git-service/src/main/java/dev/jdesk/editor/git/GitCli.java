package dev.jdesk.editor.git;

import dev.jdesk.editor.api.EditorErrorCode;
import dev.jdesk.editor.api.EditorException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the system {@code git} with argument arrays only — never a concatenated shell string
 * (spec §19, §21). Output is bounded and each call is timeboxed; a hung or runaway git is killed.
 */
public final class GitCli {

    /** @param exitCode process exit; {@code stdout}/{@code stderr} captured (bounded). */
    public record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    private static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final Path cwd;
    private final String gitExecutable;

    public GitCli(Path cwd) {
        this(cwd, "git");
    }

    public GitCli(Path cwd, String gitExecutable) {
        this.cwd = cwd;
        this.gitExecutable = gitExecutable;
    }

    public Result run(String... args) {
        List<String> argv = new ArrayList<>(args.length + 1);
        argv.add(gitExecutable);
        argv.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(cwd.toFile());
            // Minimal, deterministic environment; never inherit interactive prompts.
            Map<String, String> env = builder.environment();
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put("GIT_OPTIONAL_LOCKS", "0");
            Process process = builder.start();
            String stdout = readBounded(process.getInputStream());
            String stderr = readBounded(process.getErrorStream());
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new EditorException(EditorErrorCode.TIMEOUT, "git timed out: " + args[0]);
            }
            return new Result(process.exitValue(), stdout, stderr);
        } catch (IOException e) {
            throw new EditorException(EditorErrorCode.PROCESS_FAILED, "git could not be started", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EditorException(EditorErrorCode.INTERNAL_ERROR, "interrupted running git", e);
        }
    }

    private static String readBounded(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        var out = new java.io.ByteArrayOutputStream();
        int read;
        long total = 0;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                break;
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
