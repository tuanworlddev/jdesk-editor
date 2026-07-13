/**
 * Git integration over the system {@code git} CLI (spec §19). Read-only in version 1: branch,
 * file status, and text diffs for the Monaco diff editor. Every invocation uses a
 * {@code ProcessBuilder} argument array — never a concatenated shell string.
 */
module dev.jdesk.editor.git {
    requires transitive dev.jdesk.editor.api;

    exports dev.jdesk.editor.git;
}
