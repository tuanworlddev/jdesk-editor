package dev.jdesk.editor.lsp;

import java.util.List;

/**
 * How to launch a language server for a language id (spec §18). The command is an argument array
 * (never a shell string); resolution prefers the pinned servers under {@code tools/lsp}.
 *
 * @param languageId LSP language id (e.g. {@code typescript}, {@code python})
 * @param command process argv, e.g. {@code [node, …/typescript-language-server, --stdio]}
 */
public record LspServerConfig(String languageId, List<String> command) {

    /** typescript-language-server over stdio from the pinned tools/lsp install. */
    public static LspServerConfig typescript(String toolsLspBin) {
        return new LspServerConfig("typescript",
                List.of(toolsLspBin + "/typescript-language-server", "--stdio"));
    }

    /** pyright-langserver over stdio from the pinned tools/lsp install. */
    public static LspServerConfig pyright(String toolsLspBin) {
        return new LspServerConfig("python",
                List.of(toolsLspBin + "/pyright-langserver", "--stdio"));
    }
}
