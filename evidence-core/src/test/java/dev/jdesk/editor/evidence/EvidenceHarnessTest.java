package dev.jdesk.editor.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the honesty rules of the evidence harness actually bite. These are the tests that make
 * every downstream PASS trustworthy: a run cannot claim PASS without real evidence, cannot start
 * against a drifted framework checkout, and a tampered run is caught by the verifier.
 */
class EvidenceHarnessTest {

    /** Builds an Environment whose editor + JDesk repos are real temp git repos at a known SHA. */
    private Environment newEnvironment(Path root) throws Exception {
        Path repo = Files.createDirectories(root.resolve("editor"));
        Path jdesk = Files.createDirectories(root.resolve("jdesk"));
        String jdeskSha = initGitRepo(jdesk);
        initGitRepo(repo);
        Files.writeString(repo.resolve("gradle.properties"),
                "jdeskSource=../jdesk\njdeskPinnedSha=" + jdeskSha + "\n");
        return new Environment(repo, repo.resolve("artifacts"), jdesk, jdeskSha);
    }

    private String initGitRepo(Path dir) throws Exception {
        run(dir, "git", "init", "-q", "-b", "main");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("seed.txt"), "seed");
        run(dir, "git", "add", "-A");
        run(dir, "git", "commit", "-q", "-m", "seed");
        Process p = new ProcessBuilder("git", "-C", dir.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true).start();
        String sha = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return sha;
    }

    private void run(Path dir, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(dir.toFile())
                .redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("cmd failed: " + String.join(" ", argv)
                    + "\n" + new String(out, StandardCharsets.UTF_8));
        }
    }

    @Test
    void startsAndFinishesAHonestPassRun(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        Path evidence = run.file("proof.txt");
        Files.writeString(evidence, "real evidence");
        run.addResult(TestResult.pass("SELF-01", 5,
                List.of(run.dir().relativize(evidence).toString()), "wrote proof"));
        RunOutcome outcome = run.finish();

        assertThat(outcome).isEqualTo(RunOutcome.PASS);
        assertThat(JsonIo.read(run.dir().resolve("manifest.json")).path("status").asText())
                .isEqualTo("COMPLETE");
        assertThat(new RunVerifier().verify(run.dir())).isEmpty();
    }

    @Test
    void rejectsPassWithoutEvidence(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        assertThatThrownBy(() ->
                run.addResult(TestResult.pass("SELF-02", 1, List.of(), "no proof")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PASS without evidence");
    }

    @Test
    void rejectsPassWhenEvidenceFileIsMissing(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        assertThatThrownBy(() ->
                run.addResult(TestResult.pass("SELF-03", 1, List.of("ghost.txt"), "fabricated")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence missing");
    }

    @Test
    void refusesToStartWhenFrameworkDrifted(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        Environment drifted = new Environment(env.repoRoot(), env.artifactsDir(),
                env.jdeskSource(), "0000000000000000000000000000000000000000");
        assertThatThrownBy(() -> TestRun.start(drifted, "gate", "self-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provenance failure");
    }

    @Test
    void refusesToStartWhenFrameworkTreeDirty(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        Files.writeString(env.jdeskSource().resolve("uncommitted.txt"), "dirty");
        assertThatThrownBy(() -> TestRun.start(env, "gate", "self-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dirty");
    }

    @Test
    void verifierCatchesTamperedEvidence(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        Path evidence = run.file("proof.txt");
        Files.writeString(evidence, "original");
        run.addResult(TestResult.pass("SELF-04", 1,
                List.of(run.dir().relativize(evidence).toString()), "proof"));
        run.finish();

        // Tamper after checksums were written.
        Files.writeString(evidence, "TAMPERED");
        List<RunVerifier.Violation> violations = new RunVerifier().verify(run.dir());
        assertThat(violations).anyMatch(v -> v.rule().equals("checksum-mismatch"));
    }

    @Test
    void nativeCategoryRequiresRealWebViewBackend(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "gate", "self-test");
        Path evidence = run.file("proof.txt");
        Files.writeString(evidence, "x");
        run.addResult(TestResult.pass("GATE-X", 1,
                List.of(run.dir().relativize(evidence).toString()), "proof"));
        run.finish(); // no app-info.json merged → backend unknown

        assertThat(new RunVerifier().verify(run.dir()))
                .anyMatch(v -> v.rule().equals("webview-unproven"));
    }

    @Test
    void commandRecorderCapturesExitCodes(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        CommandRecorder.CommandResult ok = run.commands().run(
                List.of("sh", "-c", "echo hello"), env.repoRoot(), Map.of(), Duration.ofSeconds(10));
        CommandRecorder.CommandResult bad = run.commands().run(
                List.of("sh", "-c", "exit 7"), env.repoRoot(), Map.of(), Duration.ofSeconds(10));

        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.stdout()).contains("hello");
        assertThat(bad.exitCode()).isEqualTo(7);
        assertThat(bad.succeeded()).isFalse();
        assertThat(Files.readString(run.dir().resolve("commands.jsonl"))).contains("\"exitCode\":7");
    }

    @Test
    void successfulCommandWithEmptyStderrStillPasses(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        CommandRecorder.CommandResult ok = run.commands().run(
                List.of("sh", "-c", "echo only-stdout"), env.repoRoot(), Map.of(), Duration.ofSeconds(10));
        // stderr is legitimately empty; addCommandResult must not treat that as missing evidence.
        run.addCommandResult("SELF-EMPTY", ok, "command with clean stderr");
        assertThat(run.finish()).isEqualTo(RunOutcome.PASS);
        assertThat(new RunVerifier().verify(run.dir())).isEmpty();
    }

    @Test
    void blockedRunIsHonestNotPass(@TempDir Path root) throws Exception {
        Environment env = newEnvironment(root);
        TestRun run = TestRun.start(env, "unit", "self-test");
        run.addResult(TestResult.blocked("SELF-05", "codex executable not found", 3));
        RunOutcome outcome = run.finish();

        assertThat(outcome).isEqualTo(RunOutcome.BLOCKED);
        assertThat(new RunVerifier().verify(run.dir())).isEmpty();
    }
}
