package org.tymz.version;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.tymz.config.Config;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Version Manager for Git repository version analysis.
 * Handles Git tag discovery, version chronology, and version-based data splits.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 * 
 * This class enables version-based validation where models are trained
 * on historical versions and tested on future releases, providing realistic
 * performance evaluation for practical deployment scenarios.
 */
public class VersionManager {
    
    /**
     * Represents a Git version (tag) with associated metadata
     */
    public static class Version {
        public final String name;
        public final RevCommit commit;
        public final long timestamp;
        public final LocalDateTime dateTime;
        public final String commitHash;

        Version(String name, RevCommit commit) {
            this.name = name.replace("refs/tags/", "");
            this.commit = commit;
            this.timestamp = commit.getCommitTime();
            this.dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(commit.getCommitTime()), 
                ZoneOffset.UTC
            );
            this.commitHash = commit.getName();
        }
        
        @Override 
        public String toString() { 
            return String.format("%s (%s)", name, dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        }
        
        public String getFormattedInfo() {
            return String.format("Version: %s | Date: %s | Commit: %s", 
                name, 
                dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                commitHash.substring(0, Math.min(8, commitHash.length()))
            );
        }
    }

    /**
     * Retrieves all Git tags from the repository and converts them to Version objects.
     * Versions are sorted chronologically from oldest to newest based on commit time.
     * 
     * @param git Git instance for the repository
     * @return List of Version objects sorted chronologically
     * @throws GitAPIException if Git operations fail
     * @throws IOException if repository access fails
     */
    public List<Version> getAllVersions(Git git) throws GitAPIException, IOException {
        System.out.println("Discovering versions (Git tags) in repository...");
        
        List<Ref> tags = git.tagList().call();
        List<Version> versions = new ArrayList<>();
        
        try (RevWalk revWalk = new RevWalk(git.getRepository())) {
            for (Ref tag : tags) {
                try {
                    RevCommit commit = revWalk.parseCommit(tag.getObjectId());
                    Version version = new Version(tag.getName(), commit);
                    versions.add(version);
                    
                    if (Config.isLoggingVerbose()) {
                        System.out.println("  Found: " + version.getFormattedInfo());
                    }
                } catch (IOException e) {
                    System.out.println("  Warning: Could not parse tag " + tag.getName() + ": " + e.getMessage());
                }
            }
        }
        
        // Sort chronologically (oldest first)
        versions.sort(Comparator.comparingLong(v -> v.timestamp));
        
        System.out.println("✓ Discovered " + versions.size() + " versions");
        if (!versions.isEmpty()) {
            System.out.println("  Earliest: " + versions.get(0));
            System.out.println("  Latest: " + versions.get(versions.size() - 1));
        }
        
        return versions;
    }
    
    /**
     * Validates that the repository has sufficient versions for version-based validation.
     * 
     * @param versions List of discovered versions
     * @return true if sufficient versions are available (≥2)
     */
    public boolean hasSufficientVersionsForValidation(List<Version> versions) {
        return versions != null && versions.size() >= 2;
    }
    
    /**
     * Gets validation summary for version-based analysis.
     * 
     * @param trainVersion Last version included in training set
     * @param testVersion Version used for testing
     * @return Formatted summary string
     */
    public String getValidationSummary(Version trainVersion, Version testVersion) {
        return String.format(
            "Version-Based Validation: Training up to %s → Testing on %s",
            trainVersion.name, testVersion.name
        );
    }
}
