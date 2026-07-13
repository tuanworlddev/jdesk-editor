package dev.jdesk.editor.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Exercises Git against a REAL {@code git} process on a real temporary repository (spec §24.2,
 * §19; a DoD acceptance requirement). Skipped only if git is not installed.
 */
class GitServiceIT {

    private void git(Path repo, String... args) throws IOException, InterruptedException {
        var argv = new java.util.ArrayList<String>();
        argv.add("git");
        argv.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(argv).directory(repo.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    private Path initRepo(Path root) throws IOException, InterruptedException {
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "Test");
        return root;
    }

    private boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void reportsBranchAndCleanStatusForACommittedRepo(@TempDir Path root) throws Exception {
        assumeThat(gitAvailable()).isTrue();
        initRepo(root);
        Files.writeString(root.resolve("a.txt"), "hello\n");
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "init");

        GitService service = new GitService(root);

        assertThat(service.available()).isTrue();
        GitService.Status status = service.status();
        assertThat(status.branch()).isEqualTo("main");
        assertThat(status.files()).isEmpty();
    }

    @Test
    void reportsModifiedAndUntrackedFiles(@TempDir Path root) throws Exception {
        assumeThat(gitAvailable()).isTrue();
        initRepo(root);
        Files.writeString(root.resolve("tracked.txt"), "v1\n");
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "init");
        Files.writeString(root.resolve("tracked.txt"), "v2\n");        // modified
        Files.writeString(root.resolve("new.txt"), "brand new\n");     // untracked

        GitService.Status status = new GitService(root).status();

        assertThat(status.files()).extracting(GitService.FileStatus::relPath)
                .contains("tracked.txt", "new.txt");
        assertThat(status.files()).filteredOn(f -> f.relPath().equals("new.txt")).first()
                .extracting(GitService.FileStatus::untracked).isEqualTo(true);
        assertThat(status.files()).filteredOn(f -> f.relPath().equals("tracked.txt")).first()
                .extracting(GitService.FileStatus::worktree).isEqualTo('M');
    }

    @Test
    void showsHeadBlobForTheDiffEditor(@TempDir Path root) throws Exception {
        assumeThat(gitAvailable()).isTrue();
        initRepo(root);
        Files.writeString(root.resolve("code.txt"), "committed line\n");
        git(root, "add", "-A");
        git(root, "commit", "-q", "-m", "init");
        Files.writeString(root.resolve("code.txt"), "changed line\n"); // working copy differs

        GitService service = new GitService(root);
        // The diff editor's "original" side is the committed content.
        assertThat(service.showBlob("HEAD", "code.txt")).isEqualTo("committed line\n");
        // Working copy is the "modified" side (read directly).
        assertThat(Files.readString(root.resolve("code.txt"), StandardCharsets.UTF_8))
                .isEqualTo("changed line\n");
    }

    @Test
    void reportsUnavailableOutsideARepository(@TempDir Path root) {
        assumeThat(gitAvailable()).isTrue();
        GitService service = new GitService(root); // no git init
        assertThat(service.available()).isFalse();
        assertThat(service.status().available()).isFalse();
    }

    @Test
    void countsAheadBehindAgainstUpstream(@TempDir Path root) throws Exception {
        assumeThat(gitAvailable()).isTrue();
        // A bare "remote" and a clone give us a real upstream to be ahead of.
        Path remote = Files.createDirectories(root.resolve("remote.git"));
        git(remote, "init", "-q", "--bare", "-b", "main");
        Path clone = root.resolve("clone");
        new ProcessBuilder("git", "clone", "-q", remote.toString(), clone.toString())
                .redirectErrorStream(true).start().waitFor();
        git(clone, "config", "user.email", "t@example.com");
        git(clone, "config", "user.name", "Test");
        Files.writeString(clone.resolve("f.txt"), "one\n");
        git(clone, "add", "-A");
        git(clone, "commit", "-q", "-m", "c1");
        git(clone, "push", "-q", "origin", "main");
        Files.writeString(clone.resolve("f.txt"), "two\n");
        git(clone, "commit", "-qam", "c2"); // one commit ahead of origin/main

        GitService.Status status = new GitService(clone).status();
        assertThat(status.ahead()).isEqualTo(1);
    }
}
