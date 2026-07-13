package dev.jdesk.editor.evidence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line front-end to the evidence harness. Subcommands:
 *
 * <ul>
 *   <li>{@code wrap <category> <suite> <testId> -- <argv...>} — run one command as a whole
 *       verification run, PASS iff it exits 0;</li>
 *   <li>{@code verify [<test-runs-dir>]} — audit runs, exit 1 on any violation;</li>
 *   <li>{@code report} — regenerate the VERIFICATION.md acceptance table;</li>
 *   <li>{@code cite <runId>} — copy a run's tamper-evident stub into docs/verification/runs.</li>
 * </ul>
 */
public final class Cli {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
            return;
        }
        Environment environment = Environment.fromRepo(Path.of("").toAbsolutePath());
        String command = args[0];
        try {
            int exit = switch (command) {
                case "wrap" -> wrap(environment, Arrays.copyOfRange(args, 1, args.length));
                case "app-run" -> appRun(environment, Arrays.copyOfRange(args, 1, args.length));
                case "verify" -> verify(environment, Arrays.copyOfRange(args, 1, args.length));
                case "report" -> report(environment);
                case "cite" -> cite(environment, Arrays.copyOfRange(args, 1, args.length));
                default -> {
                    usage();
                    yield 2;
                }
            };
            System.exit(exit);
        } catch (Exception e) {
            System.err.println("evidence-cli " + command + " failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int wrap(Environment environment, String[] args) {
        int separator = Arrays.asList(args).indexOf("--");
        if (separator < 3) {
            System.err.println("usage: wrap <category> <suite> <testId> -- <argv...>");
            return 2;
        }
        String category = args[0];
        String suite = args[1];
        String testId = args[2];
        List<String> argv = Arrays.asList(args).subList(separator + 1, args.length);

        TestRun run = TestRun.start(environment, category, suite);
        CommandRecorder.CommandResult result =
                run.commands().run(argv, environment.repoRoot(), java.util.Map.of(), Duration.ofMinutes(30));
        run.addCommandResult(testId, result, "wrapped command: " + String.join(" ", argv));
        RunOutcome outcome = run.finish();
        System.out.println("run " + run.runId() + " -> " + outcome);
        return outcome == RunOutcome.PASS ? 0 : 1;
    }

    /**
     * Runs a self-driving app that writes {@code gate-results.json}, {@code app-info.json},
     * {@code console.json}, and {@code screenshots/} into the run directory (passed via
     * {@code JDESK_EDITOR_RUN_DIR}), then ingests those results and the real WebView backend.
     */
    private static int appRun(Environment environment, String[] args) {
        int separator = Arrays.asList(args).indexOf("--");
        if (separator < 2) {
            System.err.println("usage: app-run <category> <suite> -- <argv...>");
            return 2;
        }
        String category = args[0];
        String suite = args[1];
        List<String> argv = Arrays.asList(args).subList(separator + 1, args.length);

        TestRun run = TestRun.start(environment, category, suite);
        CommandRecorder.CommandResult result = run.commands().run(argv, environment.repoRoot(),
                java.util.Map.of("JDESK_EDITOR_RUN_DIR", run.dir().toString()), Duration.ofMinutes(15));
        run.mergeAppInfo();
        run.ingestAppResults();
        if (!result.succeeded()) {
            // Surface a non-zero app exit even if it happened to write PASS rows.
            run.addResult(TestResult.fail(suite + "-EXIT", result.durationMs(),
                    List.of("commands.jsonl"), "app exit=" + result.exitCode() + " timedOut=" + result.timedOut()));
        }
        RunOutcome outcome = run.finish();
        System.out.println("run " + run.runId() + " -> " + outcome);
        return outcome == RunOutcome.PASS ? 0 : 1;
    }

    private static int verify(Environment environment, String[] args) {
        Path target = args.length > 0
                ? Path.of(args[0])
                : environment.artifactsDir().resolve("test-runs");
        List<RunVerifier.Violation> violations = new RunVerifier().verifyAll(target);
        if (violations.isEmpty()) {
            System.out.println("verify: all runs under " + target + " are clean");
            return 0;
        }
        System.err.println("verify: " + violations.size() + " violation(s):");
        violations.forEach(v -> System.err.println("  " + v));
        return 1;
    }

    private static int report(Environment environment) {
        new ReportGenerator(environment).regenerate();
        System.out.println("report: regenerated VERIFICATION.md acceptance table");
        return 0;
    }

    private static int cite(Environment environment, String[] args) {
        if (args.length != 1) {
            System.err.println("usage: cite <runId>");
            return 2;
        }
        Path stub = CitedRuns.cite(environment, args[0]);
        System.out.println("cite: wrote " + environment.repoRoot().relativize(stub));
        return 0;
    }

    private static void usage() {
        System.err.println("""
                evidence-cli <command>
                  wrap <category> <suite> <testId> -- <argv...>
                  verify [<test-runs-dir>]
                  report
                  cite <runId>""");
    }

    private Cli() {}
}
