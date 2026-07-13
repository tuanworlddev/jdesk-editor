package dev.jdesk.editor.evidence;

import java.util.List;
import java.util.Objects;

/**
 * Result of one test within a run.
 *
 * @param id stable test identifier (e.g. {@code GATE-01}, {@code E2E-08}, {@code LIVE-CODEX-01})
 * @param outcome PASS / FAIL / BLOCKED
 * @param durationMs wall-clock duration
 * @param evidence run-relative paths proving the outcome; PASS requires at least one
 * @param detail short human-readable finding ("sha256 disk==buffer==expected")
 * @param blockedReason mandatory when outcome is BLOCKED, null otherwise
 * @param dependencyProbeSeq commands.jsonl seq of the failed dependency probe for BLOCKED
 */
public record TestResult(
        String id,
        RunOutcome outcome,
        long durationMs,
        List<String> evidence,
        String detail,
        String blockedReason,
        Integer dependencyProbeSeq) {

    public TestResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(outcome, "outcome");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (outcome == RunOutcome.BLOCKED && (blockedReason == null || blockedReason.isBlank())) {
            throw new IllegalArgumentException("BLOCKED result requires a blockedReason: " + id);
        }
    }

    public static TestResult pass(String id, long durationMs, List<String> evidence, String detail) {
        return new TestResult(id, RunOutcome.PASS, durationMs, evidence, detail, null, null);
    }

    public static TestResult fail(String id, long durationMs, List<String> evidence, String detail) {
        return new TestResult(id, RunOutcome.FAIL, durationMs, evidence, detail, null, null);
    }

    public static TestResult blocked(String id, String reason, Integer dependencyProbeSeq) {
        return new TestResult(id, RunOutcome.BLOCKED, 0, List.of(), null, reason, dependencyProbeSeq);
    }
}
