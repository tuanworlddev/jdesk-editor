package dev.jdesk.editor.api;

/**
 * Carries an {@link EditorErrorCode} across the editor's internal boundaries. Messages are
 * expected to be workspace-relative and secret-free (the app layer redacts before surfacing).
 */
public final class EditorException extends RuntimeException {

    private final EditorErrorCode code;

    public EditorException(EditorErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EditorException(EditorErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public EditorErrorCode code() {
        return code;
    }

    public static EditorException of(EditorErrorCode code, String message) {
        return new EditorException(code, message);
    }
}
