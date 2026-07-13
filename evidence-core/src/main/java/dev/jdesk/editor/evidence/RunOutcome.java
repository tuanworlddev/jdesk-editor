package dev.jdesk.editor.evidence;

/** Outcome of a single test or an entire run. There is no fourth state. */
public enum RunOutcome {
    PASS,
    FAIL,
    BLOCKED
}
