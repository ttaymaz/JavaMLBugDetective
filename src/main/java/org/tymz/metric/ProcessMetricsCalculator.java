package org.tymz.metric;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.tymz.config.Config;
import org.tymz.db.DatabaseManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Enhanced Process Metrics Calculator with Single-Pass Architecture and Diff/Churn Metrics.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * This class implements a single-pass, chronological processing approach to calculate:
 * 
 * Traditional Process Metrics:
 * - NR (Number of Revisions): Total times a file has been modified
 * - NDEV (Number of Developers): Unique developers who have modified the file
 * - AGE: Age of the file from its first appearance to current commit
 * - EXP (Developer Experience): Times the current author has modified this file before
 * 
 * Enhanced Diff/Churn Metrics (NEW):
 * - LINES_ADDED: Number of lines added in the current change
 * - LINES_DELETED: Number of lines deleted in the current change
 * - HUNK_COUNT: Number of separate change hunks (measures change dispersion/entropy)
 * 
 * This single-pass architecture eliminates target leakage and provides
 * features that better capture change size and dispersion patterns for improved ML prediction.
 */
public class ProcessMetricsCalculator {
    
    private final DatabaseManager databaseManager;
    
    // In-memory state tracking for single-pass processing
    private Map<String, Integer> fileRevisionCounts;           // NR tracking
    private Map<String, Set<String>> fileDevelopers;          // NDEV tracking  
    private Map<String, LocalDateTime> fileFirstAppearance;   // AGE tracking
    private Map<String, Map<String, Integer>> developerExperience; // EXP tracking
    
    /**
     * Constructs a ProcessMetricsCalculator with single-pass architecture.
     * 
     * @param databaseManager Database manager for storing computed metrics
     */
    public ProcessMetricsCalculator(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        initializeTracking();
    }
    
    /**
     * Initializes the in-memory tracking structures for single-pass processing.
     */
    private void initializeTracking() {
        this.fileRevisionCounts = new HashMap<>();
        this.fileDevelopers = new HashMap<>();
        this.fileFirstAppearance = new HashMap<>();
        this.developerExperience = new HashMap<>();
    }
    
    /**
     * Main method to calculate and save process metrics for all commits in chronological order.
     * This is the single entry point that replaces the old two-phase approach.
     * 
     * Implements single-pass processing that:
     * 1. Processes commits in chronological order (oldest to newest)
     * 2. Tracks cumulative state for traditional metrics (NR, NDEV, AGE, EXP)
     * 3. Calculates diff-based metrics (LINES_ADDED, LINES_DELETED, HUNK_COUNT) for each commit
     * 4. Stores metrics in database immediately to avoid memory issues
     * 
     * @param repository Git repository to analyze
     * @throws IOException if repository access fails
     * @throws SQLException if database operations fail
     */
    public void calculateAndSaveMetricsForAllCommits(Repository repository) throws IOException, SQLException {
        System.out.println("🚀 Starting single-pass process metrics calculation with diff/churn metrics...");
        
        try (RevWalk revWalk = new RevWalk(repository);
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
             ObjectReader objectReader = repository.newObjectReader()) {
            
            diffFormatter.setRepository(repository);
            diffFormatter.setDetectRenames(true);
            
            // Get all commits in chronological order (oldest first)
            List<RevCommit> allCommits = getAllCommitsChronological(repository, revWalk);
            System.out.println("📊 Processing " + allCommits.size() + " commits chronologically...");
            
            RevCommit previousCommit = null;
            int processedCount = 0;
            
            for (RevCommit commit : allCommits) {
                processedCount++;
                if (processedCount % 100 == 0) {
                    System.out.println("   Processed " + processedCount + "/" + allCommits.size() + " commits");
                }
                
                // Calculate metrics for this commit
                processCommitMetrics(repository, diffFormatter, objectReader, commit, previousCommit);
                previousCommit = commit;
            }
            
            System.out.println("✅ Single-pass processing completed: " + processedCount + " commits processed");
            printMetricsSummary();
        }
    }
    
    /**
     * Gets commits in chronological order (oldest first) with max.commits limit applied.
     */
    private List<RevCommit> getAllCommitsChronological(Repository repository, RevWalk revWalk) throws IOException {
        revWalk.sort(RevSort.COMMIT_TIME_DESC); // Start with newest first
        revWalk.markStart(revWalk.parseCommit(repository.resolve("HEAD")));
        
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit : revWalk) {
            commits.add(commit);
        }
        
        // Apply max.commits limit from configuration
        int maxCommits = Integer.parseInt(Config.getProperty("max.commits", "0"));
        if (maxCommits > 0 && commits.size() > maxCommits) {
            commits = commits.subList(0, maxCommits);
            System.out.println("📊 Applied max.commits limit: processing " + maxCommits + " latest commits only");
        }
        
        // Reverse to get chronological order (oldest first)
        Collections.reverse(commits);
        return commits;
    }
    
    /**
     * Processes a single commit to calculate both traditional and diff/churn metrics.
     */
    private void processCommitMetrics(Repository repository, DiffFormatter diffFormatter, 
                                    ObjectReader objectReader, RevCommit commit, 
                                    RevCommit previousCommit) throws IOException, SQLException {
        
        LocalDateTime commitDate = LocalDateTime.ofInstant(
            commit.getCommitterIdent().getWhenAsInstant(), 
            ZoneId.systemDefault()
        );
        String authorEmail = commit.getAuthorIdent().getEmailAddress();
        String commitHash = commit.name();
        
        // Insert commit into database
        databaseManager.insertCommit(
            commitHash,
            commit.getAuthorIdent().getName(),
            authorEmail,
            commitDate,
            commit.getShortMessage(),
            false // Bug-fixing status will be determined later
        );
        
        // Get diff entries for this commit
        List<DiffEntry> diffEntries = getDiffEntries(diffFormatter, objectReader, 
                                                   previousCommit, commit);
        
        for (DiffEntry diffEntry : diffEntries) {
            String filePath = getFilePath(diffEntry);
            
            // Only process .java files
            if (!filePath.endsWith(".java")) {
                continue;
            }
            
            // Calculate diff/churn metrics for this specific change
            DiffMetrics diffMetrics = calculateDiffMetrics(repository, diffEntry);
            
            // Update cumulative state for traditional metrics
            updateTraditionalMetrics(filePath, authorEmail, commitDate);
            
            // Get current traditional metric values
            int nr = fileRevisionCounts.getOrDefault(filePath, 0);
            int ndev = fileDevelopers.getOrDefault(filePath, new HashSet<>()).size();
            int age = calculateAge(filePath, commitDate);
            int exp = getDeveloperExperience(filePath, authorEmail);
            
            // Save all metrics to database
            saveMetricsToDatabase(commitHash, filePath, authorEmail, commitDate,
                                nr, ndev, age, exp,
                                diffMetrics.linesAdded, diffMetrics.linesDeleted, diffMetrics.hunkCount);
        }
    }
    
    /**
     * Calculates diff metrics (lines added/deleted, hunk count) for a DiffEntry.
     */
    private DiffMetrics calculateDiffMetrics(Repository repository, DiffEntry diffEntry) throws IOException {
        int linesAdded = 0;
        int linesDeleted = 0;
        int hunkCount = 0;
        
        // Use ByteArrayOutputStream to capture diff output
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            DiffFormatter detailedFormatter = new DiffFormatter(outputStream);
            detailedFormatter.setRepository(repository);
            detailedFormatter.setDetectRenames(true);
            
            // Format the diff to get detailed information
            detailedFormatter.format(diffEntry);
            detailedFormatter.close();
            
            // Parse the diff output to count changes
            String diffOutput = outputStream.toString();
            String[] lines = diffOutput.split("\n");
            
            boolean inHunk = false;
            for (String line : lines) {
                if (line.startsWith("@@")) {
                    // New hunk detected
                    hunkCount++;
                    inHunk = true;
                } else if (inHunk && line.startsWith("+") && !line.startsWith("+++")) {
                    linesAdded++;
                } else if (inHunk && line.startsWith("-") && !line.startsWith("---")) {
                    linesDeleted++;
                }
            }
        }
        
        return new DiffMetrics(linesAdded, linesDeleted, hunkCount);
    }
    
    /**
     * Updates the cumulative state for traditional metrics (NR, NDEV, AGE, EXP).
     */
    private void updateTraditionalMetrics(String filePath, String authorEmail, LocalDateTime commitDate) {
        // Update NR (Number of Revisions)
        fileRevisionCounts.merge(filePath, 1, Integer::sum);
        
        // Update NDEV (Number of Developers)
        fileDevelopers.computeIfAbsent(filePath, k -> new HashSet<>()).add(authorEmail);
        
        // Update AGE (first appearance tracking)
        fileFirstAppearance.merge(filePath, commitDate, 
            (existing, current) -> existing.isBefore(current) ? existing : current);
        
        // Update EXP (Developer Experience)
        developerExperience.computeIfAbsent(filePath, k -> new HashMap<>())
                           .merge(authorEmail, 1, Integer::sum);
    }
    
    /**
     * Calculates the age of a file in days from its first appearance to the given date.
     */
    private int calculateAge(String filePath, LocalDateTime currentDate) {
        LocalDateTime firstAppearance = fileFirstAppearance.get(filePath);
        if (firstAppearance == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(firstAppearance, currentDate);
    }
    
    /**
     * Gets the developer's experience count for a file (excluding current commit).
     */
    private int getDeveloperExperience(String filePath, String authorEmail) {
        Map<String, Integer> devExp = developerExperience.get(filePath);
        if (devExp == null) {
            return 0;
        }
        // Subtract 1 because we've already incremented for the current commit
        return devExp.getOrDefault(authorEmail, 1) - 1;
    }
    
    /**
     * Saves all calculated metrics to the database.
     */
    private void saveMetricsToDatabase(String commitHash, String filePath, String authorEmail, 
                                     LocalDateTime commitDate, int nr, int ndev, int age, int exp,
                                     int linesAdded, int linesDeleted, int hunkCount) throws SQLException {
        
        // Get or create file revision record
        long fileRevisionId = databaseManager.insertFileRevision(
            filePath,
            commitHash,
            "MODIFY", // Change type will be refined later if needed
            linesAdded,
            linesDeleted
        );
        
        // Save traditional process metrics
        databaseManager.insertMetric(fileRevisionId, "NR", nr, "PROCESS");
        databaseManager.insertMetric(fileRevisionId, "NDEV", ndev, "PROCESS");
        databaseManager.insertMetric(fileRevisionId, "AGE", age, "PROCESS");
        databaseManager.insertMetric(fileRevisionId, "EXP", exp, "PROCESS");
        
        // Save new diff/churn metrics
        databaseManager.insertMetric(fileRevisionId, "LINES_ADDED", linesAdded, "PROCESS");
        databaseManager.insertMetric(fileRevisionId, "LINES_DELETED", linesDeleted, "PROCESS");
        databaseManager.insertMetric(fileRevisionId, "HUNK_COUNT", hunkCount, "PROCESS");
    }
    
    /**
     * Gets diff entries between two commits.
     */
    private List<DiffEntry> getDiffEntries(DiffFormatter diffFormatter, ObjectReader objectReader,
                                          RevCommit parentCommit, RevCommit currentCommit) throws IOException {
        
        if (parentCommit == null) {
            // Root commit - no diffs
            return new ArrayList<>();
        }
        
        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
        oldTreeIter.reset(objectReader, parentCommit.getTree());
        
        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        newTreeIter.reset(objectReader, currentCommit.getTree());
        
        return diffFormatter.scan(oldTreeIter, newTreeIter);
    }
    
    /**
     * Gets the file path from a diff entry, handling new and deleted files.
     */
    private String getFilePath(DiffEntry diffEntry) {
        if (diffEntry.getOldPath().equals("/dev/null")) {
            return diffEntry.getNewPath(); // New file
        } else if (diffEntry.getNewPath().equals("/dev/null")) {
            return diffEntry.getOldPath(); // Deleted file
        } else {
            return diffEntry.getNewPath(); // Modified or renamed file
        }
    }
    
    /**
     * Prints summary of calculated metrics.
     */
    private void printMetricsSummary() {
        System.out.println("\n📈 Process Metrics Summary:");
        System.out.println("- Files tracked: " + fileRevisionCounts.size());
        System.out.println("- Total file revisions: " + fileRevisionCounts.values().stream().mapToInt(Integer::intValue).sum());
        System.out.println("- Unique developers: " + fileDevelopers.values().stream().mapToInt(Set::size).sum());
        
        // Calculate average revisions per file
        double avgRevisionsPerFile = fileRevisionCounts.values().stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        System.out.printf("- Average revisions per file: %.2f%n", avgRevisionsPerFile);
        
        System.out.println("\n🎯 Enhanced metrics calculated:");
        System.out.println("- Traditional metrics: NR, NDEV, AGE, EXP");
        System.out.println("- New diff/churn metrics: LINES_ADDED, LINES_DELETED, HUNK_COUNT");
    }
    
    /**
     * Data class to hold diff/churn metrics.
     */
    private static class DiffMetrics {
        final int linesAdded;
        final int linesDeleted;
        final int hunkCount;
        
        DiffMetrics(int linesAdded, int linesDeleted, int hunkCount) {
            this.linesAdded = linesAdded;
            this.linesDeleted = linesDeleted;
            this.hunkCount = hunkCount;
        }
        
        @Override
        public String toString() {
            return String.format("DiffMetrics{added=%d, deleted=%d, hunks=%d}", 
                linesAdded, linesDeleted, hunkCount);
        }
    }
}
