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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Timing-only benchmark that exercises the same JGit diff+blame tracing cost
 * as SZZBugLabeler.traceBugIntroducingCommits, on a sample of real bug-fixing
 * commits, to estimate full-history wall-clock time before committing to a
 * full read-only re-run. Performs no database writes.
 *
 * Usage: SzzTimingBenchmark <repoPath> [sampleSize]
 * Sample commits are chosen with an even stride across the full ordered list
 * of matched bug-fixing commits (not just the most recent N), since per-commit
 * blame cost varies with a commit's position in history and a recency-only
 * sample under/over-estimates the true average.
 */
public class SzzTimingBenchmark {

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

    public static void main(String[] args) throws Exception {
        String repoPath = args[0];
        int sampleSize = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        File gitDir = new File(repoPath, ".git");
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir).build();
             Git git = new Git(repository)) {

            long walkStart = System.nanoTime();
            List<RevCommit> allCommits = new ArrayList<>();
            try (RevWalk revWalk = new RevWalk(repository)) {
                revWalk.markStart(revWalk.parseCommit(repository.resolve("HEAD")));
                for (RevCommit c : revWalk) {
                    allCommits.add(c);
                }
            }
            long walkMs = (System.nanoTime() - walkStart) / 1_000_000;
            System.out.printf("REVWALK_MS=%d TOTAL_COMMITS=%d%n", walkMs, allCommits.size());

            List<RevCommit> bugFixCommits = new ArrayList<>();
            long scanStart = System.nanoTime();
            for (RevCommit c : allCommits) {
                String msg = c.getFullMessage().toLowerCase();
                boolean isBugFix = BUG_FIX_PATTERNS.stream().anyMatch(p -> p.matcher(msg).find());
                if (isBugFix) {
                    bugFixCommits.add(c);
                }
            }
            long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
            System.out.printf("MESSAGE_SCAN_MS=%d BUGFIX_COMMITS=%d%n", scanMs, bugFixCommits.size());

            int n = Math.min(sampleSize, bugFixCommits.size());
            List<RevCommit> sample = new ArrayList<>();
            if (n > 0) {
                double stride = (double) bugFixCommits.size() / n;
                for (int i = 0; i < n; i++) {
                    sample.add(bugFixCommits.get((int) (i * stride)));
                }
            }

            long traceStart = System.nanoTime();
            int totalFilesBlamed = 0;
            for (RevCommit fixCommit : sample) {
                RevCommit[] parents = fixCommit.getParents();
                if (parents.length == 0) continue;
                RevCommit parentCommit = parents[0];

                List<DiffEntry> diffs = getModifiedJavaFiles(repository, parentCommit, fixCommit);
                for (DiffEntry diff : diffs) {
                    if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) continue;
                    String filePath = diff.getOldPath().equals("/dev/null") ? diff.getNewPath() : diff.getOldPath();

                    List<int[]> deletedRanges = getDeletedLineRanges(repository, diff);
                    if (deletedRanges.isEmpty()) continue;

                    BlameCommand blameCommand = git.blame().setFilePath(filePath).setStartCommit(parentCommit);
                    BlameResult blameResult = blameCommand.call();
                    totalFilesBlamed++;
                    if (blameResult == null) continue;
                    for (int[] range : deletedRanges) {
                        for (int line = range[0]; line <= range[1]; line++) {
                            int idx = line - 1;
                            if (idx >= 0 && idx < blameResult.getResultContents().size()) {
                                blameResult.getSourceCommit(idx);
                            }
                        }
                    }
                }
            }
            long traceMs = (System.nanoTime() - traceStart) / 1_000_000;
            System.out.printf("SAMPLE_SIZE=%d FILES_BLAMED=%d TRACE_MS=%d MS_PER_FIXCOMMIT=%.1f%n",
                    n, totalFilesBlamed, traceMs, n == 0 ? 0.0 : (double) traceMs / n);

            double estimatedTotalTraceMs = bugFixCommits.isEmpty() || n == 0
                    ? 0.0
                    : ((double) traceMs / n) * bugFixCommits.size();
            System.out.printf("ESTIMATED_TOTAL_TRACE_MS=%.0f ESTIMATED_TOTAL_TRACE_MIN=%.1f%n",
                    estimatedTotalTraceMs, estimatedTotalTraceMs / 60000.0);
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
