package dev.jdesk.editor.core.doc;

/** How a document relates to its backing file after an external (non-editor) change (spec §16). */
public enum ExternalChangeState {
    /** In sync with disk, or the only writer is the editor. */
    NONE,
    /** A clean document was refreshed from a newer disk version. */
    RELOADED,
    /** Disk changed under a dirty buffer — needs human resolution, never silently overwritten. */
    CONFLICT,
    /** The backing file was deleted on disk while open. */
    DELETED
}
