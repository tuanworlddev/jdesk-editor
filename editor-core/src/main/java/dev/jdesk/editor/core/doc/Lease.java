package dev.jdesk.editor.core.doc;

/**
 * The single active writer of a document (spec §11.2). Only one writer may hold a document at a
 * time; human typing takes priority and may interrupt an agent lease.
 *
 * @param kind who holds the lease
 * @param ownerId agent session id when {@code kind == AGENT}, else null
 */
public record Lease(Kind kind, String ownerId) {

    public enum Kind { NONE, HUMAN, AGENT }

    public static final Lease NONE = new Lease(Kind.NONE, null);

    public static Lease human() {
        return new Lease(Kind.HUMAN, null);
    }

    public static Lease agent(String sessionId) {
        return new Lease(Kind.AGENT, sessionId);
    }

    public boolean isHeld() {
        return kind != Kind.NONE;
    }
}
