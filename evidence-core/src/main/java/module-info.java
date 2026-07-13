/**
 * Evidence harness for JDesk Editor verification runs (spec section 25).
 *
 * <p>Every nontrivial verification produces a run directory under {@code artifacts/test-runs/}
 * with a manifest, per-command records, collected logs, and a results file. Honesty rules are
 * enforced both at write time ({@code TestRun} refuses a PASS without evidence) and at audit
 * time ({@code RunVerifier} recomputes hashes and rejects malformed or tampered runs).
 */
module dev.jdesk.editor.evidence {
    requires transitive com.fasterxml.jackson.databind;
    requires java.net.http;

    exports dev.jdesk.editor.evidence;
}
