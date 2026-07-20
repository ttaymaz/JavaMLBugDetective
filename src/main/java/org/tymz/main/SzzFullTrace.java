package org.tymz.main;

import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only re-run of the SZZBugLabeler tracing logic (diff + blame), for
 * verification purposes only. Prints every fix_commit -> bug_introducing_commit
 * pair it derives, one per line, tab-separated. Performs no database writes and
 * no repository mutation.
 *
 * Rationale: SZZBugLabeler.storeBugLabels() never persisted the fix->BIC
 * association to the database, only a boolean is_buggy flag on the file
 * revision. This exporter closes that gap by re-deriving the mapping directly
 * from the same diff+blame logic, against local repository clones, so the
 * association can be recorded and the validation sample made independently
 * reproducible.
 *
 * Usage: SzzFullTrace <repoPath> <projectName>
 * Output: stdout receives "project\tfix_commit\tbic_commit\tszz_variant" rows;
 * stderr receives provenance/progress (PROJECT, HEAD_SHA, TOTAL_COMMITS,
 * BUGFIX_COMMITS, PROGRESS, DONE).
 */
public class SzzFullTrace {

    private static final List<Pattern> BUG_FIX_PATTERNS = List.of(
            Pattern.compile("\\bcrash\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bexception\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\berror\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfault\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfail\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnpe\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnullpointer\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bincorrect result\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("fixes?\\s+#\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("closes?\\s+#\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("resolves?\\s+#\\d+", Pattern.CASE_INSENSITIVE));

    /**
     * Usage: SzzFullTrace <repoPath> <projectName> [outputTsvFile]
     * If outputTsvFile is given, the "project\tfix_commit\tbic_commit\tszz_variant"
     * TSV (with a header row) is written there; otherwise it is written to stdout.
     * Provenance and progress always go to stderr.
     */
    public static void main(String[] args) throws Exception {
        String repoPath = args[0];
        String projectName = args[1];
        String outputTsvFile = args.length > 2 ? args[2] : null;

        PrintStream out = outputTsvFile == null
                ? System.out
                : new PrintStream(Files.newOutputStream(new File(outputTsvFile).toPath()), false, StandardCharsets.UTF_8);
        try {
            runTrace(repoPath, projectName, out);
        } finally {
            if (outputTsvFile != null) {
                out.close();
            }
        }
    }

    private static void runTrace(String repoPath, String projectName, PrintStream out) throws Exception {
        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir).build();
             Git git = new Git(repository)) {

            System.err.println("PROJECT=" + projectName);
            System.err.println("HEAD_SHA=" + repository.resolve("HEAD").getName());

            out.println("project\tfix_commit\tbic_commit\tszz_variant");

            List<RevCommit> allCommits = new ArrayList<>();
            try (RevWalk revWalk = new RevWalk(repository)) {
                revWalk.markStart(revWalk.parseCommit(repository.resolve("HEAD")));
                for (RevCommit c : revWalk) {
                    allCommits.add(c);
                }
            }
            System.err.println("TOTAL_COMMITS=" + allCommits.size());

            List<RevCommit> bugFixCommits = new ArrayList<>();
            for (RevCommit c : allCommits) {
                String msg = c.getFullMessage().toLowerCase();
                if (BUG_FIX_PATTERNS.stream().anyMatch(p -> p.matcher(msg).find())) {
                    bugFixCommits.add(c);
                }
            }
            System.err.println("BUGFIX_COMMITS=" + bugFixCommits.size());

            long start = System.currentTimeMillis();
            int processed = 0;
            for (RevCommit fixCommit : bugFixCommits) {
                RevCommit[] parents = fixCommit.getParents();
                if (parents.length == 0) {
                    processed++;
                    continue;
                }
                RevCommit parentCommit = parents[0];

                Set<String> introducingHashes = new LinkedHashSet<>();
                List<DiffEntry> diffs = getModifiedJavaFiles(repository, parentCommit, fixCommit);
                for (DiffEntry diff : diffs) {
                    if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) continue;
                    String filePath = diff.getOldPath().equals("/dev/null") ? diff.getNewPath() : diff.getOldPath();

                    List<int[]> deletedRanges = getDeletedLineRanges(repository, diff);
                    if (deletedRanges.isEmpty()) continue;

                    BlameCommand blameCommand = git.blame().setFilePath(filePath).setStartCommit(parentCommit);
                    BlameResult blameResult;
                    try {
                        blameResult = blameCommand.call();
                    } catch (Exception e) {
                        continue;
                    }
                    if (blameResult == null) continue;

                    for (int[] range : deletedRanges) {
                        for (int line = range[0]; line <= range[1]; line++) {
                            int idx = line - 1;
                            if (idx >= 0 && idx < blameResult.getResultContents().size()) {
                                RevCommit source = blameResult.getSourceCommit(idx);
                                if (source != null && !isIgnorableChange(blameResult, idx)) {
                                    introducingHashes.add(source.getName());
                                }
                            }
                        }
                    }
                }

                for (String bicHash : introducingHashes) {
                    out.printf("%s\t%s\t%s\tENHANCED%n", projectName, fixCommit.getName(), bicHash);
                }

                processed++;
                if (processed % 200 == 0) {
                    long elapsedMs = System.currentTimeMillis() - start;
                    System.err.printf("PROGRESS %d/%d elapsed_s=%d%n", processed, bugFixCommits.size(), elapsedMs / 1000);
                }
            }
            long totalMs = System.currentTimeMillis() - start;
            System.err.printf("DONE processed=%d total_s=%d%n", processed, totalMs / 1000);
        }
    }

    private static boolean isIgnorableChange(BlameResult blameResult, int lineIndex) {
        try {
            String line = blameResult.getResultContents().getString(lineIndex).trim();
            if (line.isEmpty()) return true;
            return line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.equals("*/");
        } catch (Exception e) {
            return false;
        }
    }

    private static List<DiffEntry> getModifiedJavaFiles(Repository repository, RevCommit oldCommit, RevCommit newCommit) throws Exception {
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, oldCommit.getTree());
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, newCommit.getTree());
            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                List<DiffEntry> diffs = diffFormatter.scan(oldTreeIter, newTreeIter);
                return diffs.stream()
                        .filter(diff -> {
                            String path = diff.getOldPath().equals("/dev/null") ? diff.getNewPath() : diff.getOldPath();
                            return path.endsWith(".java");
                        })
                        .toList();
            }
        }
    }

    private static List<int[]> getDeletedLineRanges(Repository repository, DiffEntry diff) throws Exception {
        List<int[]> ranges = new ArrayList<>();
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            FileHeader fileHeader = diffFormatter.toFileHeader(diff);
            for (HunkHeader hunk : fileHeader.getHunks()) {
                EditList edits = hunk.toEditList();
                for (Edit edit : edits) {
                    if (edit.getType() == Edit.Type.DELETE || edit.getType() == Edit.Type.REPLACE) {
                        int startLine = edit.getBeginA() + 1;
                        int endLine = edit.getEndA();
                        if (startLine <= endLine) {
                            ranges.add(new int[]{startLine, endLine});
                        }
                    }
                }
            }
        }
        return ranges;
    }
}
