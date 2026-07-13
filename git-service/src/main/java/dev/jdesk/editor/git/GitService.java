package dev.jdesk.editor.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only Git integration for a workspace (spec §19). Reports branch and file status and serves
 * the {@code HEAD}/index blobs the Monaco diff editor compares against the live buffer. Works with
 * no agent involved; no destructive operations are exposed in version 1.
 */
public final class GitService {

    /** One changed file. {@code index}/{@code worktree} are porcelain-v2 status codes ('M','A',…). */
    public record FileStatus(String relPath, char index, char worktree, boolean untracked) {}

    public record Status(boolean available, String branch, int ahead, int behind,
            List<FileStatus> files) {
        public static Status unavailable() {
            return new Status(false, "", 0, 0, List.of());
        }
    }

    private final GitCli git;
    private final boolean available;

    public GitService(Path workspaceRoot) {
        this.git = new GitCli(workspaceRoot);
        GitCli.Result rev = git.run("rev-parse", "--is-inside-work-tree");
        this.available = rev.ok() && rev.stdout().trim().equals("true");
    }

    public boolean available() {
        return available;
    }

    /** Parses {@code git status --porcelain=v2 --branch -z}. */
    public Status status() {
        if (!available) {
            return Status.unavailable();
        }
        GitCli.Result result = git.run("status", "--porcelain=v2", "--branch", "-z");
        if (!result.ok()) {
            return Status.unavailable();
        }
        String branch = "";
        int ahead = 0;
        int behind = 0;
        List<FileStatus> files = new ArrayList<>();
        for (String record : result.stdout().split("\0")) {
            if (record.isEmpty()) {
                continue;
            }
            if (record.startsWith("# branch.head ")) {
                branch = record.substring("# branch.head ".length()).trim();
            } else if (record.startsWith("# branch.ab ")) {
                String[] parts = record.substring("# branch.ab ".length()).trim().split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("+")) {
                        ahead = parseIntSafe(part.substring(1));
                    } else if (part.startsWith("-")) {
                        behind = parseIntSafe(part.substring(1));
                    }
                }
            } else if (record.startsWith("1 ") || record.startsWith("2 ")) {
                // "1 XY ... <path>" — XY are index/worktree status at fixed offset 2..3.
                char index = record.charAt(2);
                char worktree = record.charAt(3);
                String path = record.substring(record.lastIndexOf(' ') + 1);
                files.add(new FileStatus(path, index, worktree, false));
            } else if (record.startsWith("? ")) {
                files.add(new FileStatus(record.substring(2), '?', '?', true));
            }
            // '!' ignored entries are skipped.
        }
        return new Status(true, branch, ahead, behind, files);
    }

    public String branch() {
        if (!available) {
            return "";
        }
        GitCli.Result result = git.run("rev-parse", "--abbrev-ref", "HEAD");
        return result.ok() ? result.stdout().trim() : "";
    }

    /**
     * Returns the content of a file at a revision for the diff editor. {@code rev} is typically
     * {@code HEAD} (last commit) or {@code :0} (staged index). Returns empty when the path does not
     * exist at that revision (e.g. a newly added file).
     */
    public String showBlob(String rev, String relPath) {
        if (!available) {
            return "";
        }
        GitCli.Result result = git.run("show", rev + ":" + relPath);
        return result.ok() ? result.stdout() : "";
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
