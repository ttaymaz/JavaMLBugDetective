package org.tymz.szz;

import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
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
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.tymz.config.Config;
import org.tymz.db.DatabaseManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SZZ (Śliwerski-Zimmermann-Zeller) Bug Labeler implementation.
 * 
 * This class implements the SZZ algorithm to identify bug-introducing commits
 * by analyzing bug-fixing commits and tracing back to the original commits
 * that introduced the buggy lines of code.
 * 
 * The SZZ algorithm works in the following steps:
 * 1. Identify bug-fixing commits using commit message patterns
 * 2. For each bug-fixing commit, find the modified Java files
 * 3. Use diff analysis to find deleted/modified lines
 * 4. Use blame analysis to trace these lines back to their introducing commits
 * 5. Label the introducing commits as bug-introducing commits
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class SZZBugLabeler {
    
    private final DatabaseManager databaseManager;
    private final List<Pattern> bugFixPatterns;
    private final Set<String> processedCommits;
    
    /**
     * Constructor that initializes the SZZ bug labeler with dependency injection.
     * 
     * @param databaseManager Injected database manager instance
     */
    public SZZBugLabeler(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager; // Use injected instance
        this.bugFixPatterns = initializeBugFixPatterns();
        this.processedCommits = new HashSet<>();
        
        if (Config.isLoggingVerbose()) {
            System.out.println("SZZ Bug Labeler initialized with " + bugFixPatterns.size() + " bug-fix patterns");
        }
    }
    
    /**
     * Main orchestration method that labels bugs in the given repository.
     * 
     * This method iterates through all commits, identifies bug-fixing commits,
     * and traces them back to bug-introducing commits using the SZZ algorithm.
     * 
     * @param repository The Git repository to analyze
     * @param commits List of all commits to process
     * @throws SQLException if database operations fail
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     */
    public void labelBugs(Repository repository, List<RevCommit> commits) 
            throws SQLException, IOException, GitAPIException {
        
        System.out.println("Starting SZZ bug labeling process...");
        System.out.println("Total commits to analyze: " + commits.size());
        
        int bugFixCommits = 0;
        int bugIntroducingCommits = 0;
        
        try (Git git = new Git(repository)) {
            for (int i = 0; i < commits.size(); i++) {
                RevCommit commit = commits.get(i);
                
                // Progress reporting
                if (i % 100 == 0) {
                    System.out.printf("Progress: %d/%d commits processed (%.1f%%)%n", 
                        i, commits.size(), (i * 100.0) / commits.size());
                }
                
                // Skip if already processed
                if (processedCommits.contains(commit.getName())) {
                    continue;
                }
                
                // Check if this is a bug-fixing commit
                if (isBugFixingCommit(commit)) {
                    bugFixCommits++;
                    System.out.println("Found bug-fixing commit: " + commit.getShortMessage());
                    
                    // Trace back to bug-introducing commits
                    Set<RevCommit> buggyCommits = traceBugIntroducingCommits(git, commit);
                    bugIntroducingCommits += buggyCommits.size();
                    
                    // Store results in database
                    storeBugLabels(commit, buggyCommits, repository);
                }
                
                processedCommits.add(commit.getName());
            }
        }
        
        System.out.printf("SZZ labeling completed: %d bug-fixing commits, %d bug-introducing commits%n", 
            bugFixCommits, bugIntroducingCommits);
    }
    
    /**
     * Checks if a commit is a bug-fixing commit based on its message.
     * 
     * @param commit The commit to check
     * @return true if the commit message contains bug-fixing patterns
     */
    private boolean isBugFixingCommit(RevCommit commit) {
        String message = commit.getFullMessage().toLowerCase();
        
        return bugFixPatterns.stream()
            .anyMatch(pattern -> pattern.matcher(message).find());
    }
    
    /**
     * Traces bug-introducing commits for a given bug-fixing commit.
     * 
     * This method implements the core SZZ algorithm:
     * 1. Find modified Java files in the bug-fixing commit
     * 2. Analyze diffs to find deleted/modified lines
     * 3. Use blame to trace these lines back to their introducing commits
     * 
     * @param git Git instance for the repository
     * @param fixCommit The bug-fixing commit to analyze
     * @return Set of bug-introducing commits
     * @throws IOException if Git operations fail
     * @throws GitAPIException if Git API operations fail
     */
    private Set<RevCommit> traceBugIntroducingCommits(Git git, RevCommit fixCommit) 
            throws IOException, GitAPIException {
        
        Set<RevCommit> bugIntroducingCommits = new HashSet<>();
        Repository repository = git.getRepository();
        
        // Get parent commit (the state before the fix)
        RevCommit[] parents = fixCommit.getParents();
        if (parents.length == 0) {
            return bugIntroducingCommits; // Root commit, no parent to analyze
        }
        
        RevCommit parentCommit = parents[0]; // Use first parent for merge commits
        
        // Find modified Java files
        List<DiffEntry> diffs = getModifiedJavaFiles(repository, parentCommit, fixCommit);
        
        for (DiffEntry diff : diffs) {
            if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                continue; // Skip deleted files
            }
            
            String filePath = diff.getOldPath();
            if (filePath.equals("/dev/null")) {
                filePath = diff.getNewPath(); // New file
            }
            
            // Get deleted/modified line ranges
            List<LineRange> deletedRanges = getDeletedLineRanges(repository, diff);
            
            if (!deletedRanges.isEmpty()) {
                // Use blame to find introducing commits for deleted lines
                Set<RevCommit> introducingCommits = blameDeletedLines(git, parentCommit, filePath, deletedRanges);
                bugIntroducingCommits.addAll(introducingCommits);
            }
        }
        
        return bugIntroducingCommits;
    }
    
    /**
     * Gets the list of modified Java files between two commits.
     * 
     * @param repository The Git repository
     * @param oldCommit The older commit
     * @param newCommit The newer commit
     * @return List of diff entries for modified Java files
     * @throws IOException if Git operations fail
     */
    private List<DiffEntry> getModifiedJavaFiles(Repository repository, RevCommit oldCommit, RevCommit newCommit) 
            throws IOException {
        
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            oldTreeIter.reset(reader, oldCommit.getTree());
            
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
            newTreeIter.reset(reader, newCommit.getTree());
            
            try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                diffFormatter.setRepository(repository);
                List<DiffEntry> diffs = diffFormatter.scan(oldTreeIter, newTreeIter);
                
                // Filter for Java files only
                return diffs.stream()
                    .filter(diff -> {
                        String path = diff.getOldPath().equals("/dev/null") ? diff.getNewPath() : diff.getOldPath();
                        return path.endsWith(".java");
                    })
                    .toList();
            }
        }
    }
    
    /**
     * Gets the line ranges that were deleted or modified in a diff.
     * 
     * @param repository The Git repository
     * @param diff The diff entry to analyze
     * @return List of deleted line ranges
     * @throws IOException if Git operations fail
     */
    private List<LineRange> getDeletedLineRanges(Repository repository, DiffEntry diff) throws IOException {
        List<LineRange> deletedRanges = new ArrayList<>();
        
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            FileHeader fileHeader = diffFormatter.toFileHeader(diff);
            
            for (HunkHeader hunk : fileHeader.getHunks()) {
                EditList edits = hunk.toEditList();
                
                for (Edit edit : edits) {
                    // Focus on deletions and replacements (modifications)
                    if (edit.getType() == Edit.Type.DELETE || edit.getType() == Edit.Type.REPLACE) {
                        int startLine = edit.getBeginA() + 1; // JGit is 0-based, we want 1-based
                        int endLine = edit.getEndA();
                        
                        if (startLine <= endLine) {
                            deletedRanges.add(new LineRange(startLine, endLine));
                        }
                    }
                }
            }
        }
        
        return deletedRanges;
    }
    
    /**
     * Uses Git blame to find the commits that introduced the deleted lines.
     * 
     * @param git Git instance
     * @param commit The commit to blame (parent of fix commit)
     * @param filePath The file path to blame
     * @param deletedRanges The line ranges that were deleted
     * @return Set of commits that introduced the deleted lines
     * @throws GitAPIException if Git API operations fail
     */
    private Set<RevCommit> blameDeletedLines(Git git, RevCommit commit, String filePath, 
            List<LineRange> deletedRanges) throws GitAPIException {
        
        Set<RevCommit> introducingCommits = new HashSet<>();
        
        try {
            BlameCommand blameCommand = git.blame()
                .setFilePath(filePath)
                .setStartCommit(commit);
                
            BlameResult blameResult = blameCommand.call();
            
            if (blameResult != null) {
                for (LineRange range : deletedRanges) {
                    for (int line = range.start; line <= range.end; line++) {
                        // JGit blame is 0-based
                        int blameIndex = line - 1;
                        
                        if (blameIndex >= 0 && blameIndex < blameResult.getResultContents().size()) {
                            RevCommit sourceCommit = blameResult.getSourceCommit(blameIndex);
                            
                            if (sourceCommit != null && !isIgnorableChange(blameResult, blameIndex)) {
                                introducingCommits.add(sourceCommit);
                            }
                        }
                    }
                }
            }
        } catch (GitAPIException e) {
            // Log the error but continue processing other files
            System.err.println("Blame failed for file " + filePath + ": " + e.getMessage());
        }
        
        return introducingCommits;
    }
    
    /**
     * Checks if a change should be ignored based on heuristics.
     * 
     * This method implements simple heuristics to improve SZZ accuracy:
     * - Ignore blank lines
     * - Ignore comment-only lines
     * 
     * @param blameResult The blame result
     * @param lineIndex The line index to check
     * @return true if the change should be ignored
     */
    private boolean isIgnorableChange(BlameResult blameResult, int lineIndex) {
        try {
            String line = blameResult.getResultContents().getString(lineIndex).trim();
            
            // Ignore blank lines
            if (line.isEmpty()) {
                return true;
            }
            
            // Ignore comment-only lines
            if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") || line.equals("*/")) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            return false; // If we can't determine, don't ignore
        }
    }
    
    /**
     * Stores bug labeling results in the database.
     * 
     * @param fixCommit The bug-fixing commit
     * @param buggyCommits The set of bug-introducing commits
     * @param repository The Git repository for getting file information
     * @throws SQLException if database operations fail
     * @throws IOException if Git operations fail
     */
    private void storeBugLabels(RevCommit fixCommit, Set<RevCommit> buggyCommits, Repository repository) 
            throws SQLException, IOException {
        
            // First, ensure the fix commit is recorded
            databaseManager.insertCommit(
                fixCommit.getName(),
                fixCommit.getAuthorIdent().getName(),
                fixCommit.getAuthorIdent().getEmailAddress(),
                fixCommit.getAuthorIdent().getWhenAsInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                fixCommit.getShortMessage(),
                true // This is a bug-fixing commit
            );
            
            for (RevCommit buggyCommit : buggyCommits) {
                // Ensure the buggy commit is recorded
                databaseManager.insertCommit(
                    buggyCommit.getName(),
                    buggyCommit.getAuthorIdent().getName(),
                    buggyCommit.getAuthorIdent().getEmailAddress(),
                    buggyCommit.getAuthorIdent().getWhenAsInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
                    buggyCommit.getShortMessage(),
                    false // Initially mark as not bug-fixing (this is the bug-introducing commit)
                );
                
                // Get modified Java files for the buggy commit to create file revisions
            try {
                List<DiffEntry> diffs = getModifiedJavaFilesForCommit(repository, buggyCommit);
                
                for (DiffEntry diff : diffs) {
                    String filePath = diff.getOldPath();
                    if (filePath.equals("/dev/null")) {
                        filePath = diff.getNewPath();
                    }
                    
                    // Create file revision record
                    String changeType = getChangeTypeString(diff.getChangeType());
                    long fileRevisionId = databaseManager.insertFileRevision(
                        filePath,
                        buggyCommit.getName(),
                        changeType,
                        0, // We don't calculate lines added/deleted in SZZ context
                        0  // This will be calculated later in metrics phase
                    );
                    
                    // Label this file revision as buggy
                    databaseManager.insertBugLabel(
                        fileRevisionId,
                        true,           // Mark as buggy
                        0.8,            // Default confidence score for SZZ
                        "SZZ_ALGORITHM" // Labeled by SZZ algorithm
                    );
                }
            } catch (IOException e) {
                System.err.println("Error processing files for commit " + buggyCommit.getName() + ": " + e.getMessage());
                // Continue with other commits
            }
        }
    }
    
    /**
     * Gets the list of modified Java files for a single commit (compared to its parent).
     * 
     * @param repository The Git repository
     * @param commit The commit to analyze
     * @return List of diff entries for modified Java files
     * @throws IOException if Git operations fail
     */
    private List<DiffEntry> getModifiedJavaFilesForCommit(Repository repository, RevCommit commit) throws IOException {
        RevCommit[] parents = commit.getParents();
        if (parents.length == 0) {
            return new ArrayList<>(); // Root commit, no parent to compare
        }
        
        return getModifiedJavaFiles(repository, parents[0], commit);
    }
    
    /**
     * Converts JGit's ChangeType enum to database string representation.
     * 
     * @param changeType The JGit change type
     * @return String representation for database storage
     */
    private String getChangeTypeString(DiffEntry.ChangeType changeType) {
        return switch (changeType) {
            case ADD -> "ADD";
            case MODIFY -> "MODIFY";
            case DELETE -> "DELETE";
            case RENAME -> "RENAME";
            case COPY -> "MODIFY"; // Treat copy as modify for simplicity
        };
    }
    
    /**
     * Initializes bug fix patterns from configuration.
     * 
     * @return List of compiled regex patterns for identifying bug fixes
     */
    private List<Pattern> initializeBugFixPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        
        try {
            String bugFixKeywords = Config.getSZZBugFixKeywords();
            String[] keywords = bugFixKeywords.split(",");
            
            for (String keyword : keywords) {
                // Create case-insensitive patterns with word boundaries
                String regex = "\\b" + keyword.trim() + "\\b";
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            }
            
            // Additional patterns for common bug fix formats
            patterns.add(Pattern.compile("fixes?\\s+#\\d+", Pattern.CASE_INSENSITIVE));
            patterns.add(Pattern.compile("closes?\\s+#\\d+", Pattern.CASE_INSENSITIVE));
            patterns.add(Pattern.compile("resolves?\\s+#\\d+", Pattern.CASE_INSENSITIVE));
            
        } catch (Exception e) {
            System.err.println("Error loading bug fix patterns, using defaults: " + e.getMessage());
            
            // Default patterns if config loading fails
            patterns.add(Pattern.compile("\\bfix\\b", Pattern.CASE_INSENSITIVE));
            patterns.add(Pattern.compile("\\bbug\\b", Pattern.CASE_INSENSITIVE));
            patterns.add(Pattern.compile("\\bissue\\b", Pattern.CASE_INSENSITIVE));
        }
        
        return patterns;
    }
    
    /**
     * Simple data class to represent a line range.
     */
    private static class LineRange {
        final int start;
        final int end;
        
        LineRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        @Override
        public String toString() {
            return String.format("[%d-%d]", start, end);
        }
    }
    
    /**
     * Gets statistics about the bug labeling process.
     * 
     * @return A string with labeling statistics
     * @throws SQLException if database operations fail
     */
    public String getLabelingStats() throws SQLException {
        return databaseManager.getDatabaseStats() + 
               "\nProcessed commits: " + processedCommits.size();
    }
}
