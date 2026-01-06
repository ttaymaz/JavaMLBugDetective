package org.tymz.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.tymz.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the GitRepositoryManager class.
 * Tests repository cloning, loading, and commit history operations.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class GitRepositoryManagerTest {

    @TempDir
    Path tempDir;
    
    private GitRepositoryManager gitManager;
    private String testRepoUrl;
    private String testLocalPath;

    @BeforeEach
    void setUp() {
        gitManager = new GitRepositoryManager();
        testRepoUrl = "https://github.com/octocat/Hello-World.git";
        testLocalPath = tempDir.resolve("test-repo").toString();
    }

    @Test
    @DisplayName("Should throw exception when repository URL is not configured")
    void testLoadOrCloneRepositoryWithMissingUrl() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::getRepositoryUrl).thenReturn(null);
            configMock.when(Config::getRepositoryLocalPath).thenReturn(testLocalPath);
            configMock.when(Config::isLoggingVerbose).thenReturn(false);
            
            IllegalStateException exception = assertThrows(IllegalStateException.class, 
                () -> gitManager.loadOrCloneRepository(),
                "Should throw exception when repository URL is missing");
            
            assertTrue(exception.getMessage().contains("Repository URL is not configured"),
                      "Exception message should mention missing URL configuration");
        }
    }

    @Test
    @DisplayName("Should throw exception when local path is not configured")
    void testLoadOrCloneRepositoryWithMissingLocalPath() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::getRepositoryUrl).thenReturn(testRepoUrl);
            configMock.when(Config::getRepositoryLocalPath).thenReturn(null);
            configMock.when(Config::isLoggingVerbose).thenReturn(false);
            
            IllegalStateException exception = assertThrows(IllegalStateException.class, 
                () -> gitManager.loadOrCloneRepository(),
                "Should throw exception when local path is missing");
            
            assertTrue(exception.getMessage().contains("Local repository path is not configured"),
                      "Exception message should mention missing local path configuration");
        }
    }

    @Test
    @DisplayName("Should handle empty repository URL configuration")
    void testLoadOrCloneRepositoryWithEmptyUrl() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::getRepositoryUrl).thenReturn("   ");
            configMock.when(Config::getRepositoryLocalPath).thenReturn(testLocalPath);
            configMock.when(Config::isLoggingVerbose).thenReturn(false);
            
            IllegalStateException exception = assertThrows(IllegalStateException.class, 
                () -> gitManager.loadOrCloneRepository(),
                "Should throw exception when repository URL is empty");
            
            assertTrue(exception.getMessage().contains("Repository URL is not configured"),
                      "Exception message should mention missing URL configuration");
        }
    }

    @Test
    @DisplayName("Should validate repository correctly with isValidGitRepository")
    void testIsValidGitRepository() throws IOException {
        // Test with null path
        assertFalse(GitRepositoryManager.isValidGitRepository(null),
                   "Should return false for null path");
        
        // Test with non-existent path
        Path nonExistentPath = tempDir.resolve("non-existent");
        assertFalse(GitRepositoryManager.isValidGitRepository(nonExistentPath),
                   "Should return false for non-existent path");
        
        // Test with regular directory (not a git repo)
        Path regularDir = tempDir.resolve("regular-dir");
        Files.createDirectories(regularDir);
        assertFalse(GitRepositoryManager.isValidGitRepository(regularDir),
                   "Should return false for regular directory");
        
        // Test with directory that has .git subdirectory
        Path gitRepoDir = tempDir.resolve("git-repo");
        Path gitDir = gitRepoDir.resolve(".git");
        Files.createDirectories(gitDir);
        assertTrue(GitRepositoryManager.isValidGitRepository(gitRepoDir),
                  "Should return true for directory with .git subdirectory");
    }

    @Test
    @DisplayName("Should handle null repository in getAllCommits")
    void testGetAllCommitsWithNullRepository() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> gitManager.getAllCommits(null),
            "Should throw exception when repository is null");
        
        assertEquals("Repository cannot be null", exception.getMessage(),
                    "Exception message should indicate null repository");
    }

    @Test
    @DisplayName("Should provide repository information")
    void testGetRepositoryInfo() throws IOException {
        // Test with null repository
        String nullInfo = gitManager.getRepositoryInfo(null);
        assertEquals("Repository: null", nullInfo,
                    "Should handle null repository gracefully");
    }

    @Test
    @DisplayName("Should close repository gracefully")
    void testCloseRepository() {
        // Test with null repository (should not throw exception)
        assertDoesNotThrow(() -> gitManager.closeRepository(null),
                          "Should handle null repository closure gracefully");
    }

    @Test
    @DisplayName("Should create a test repository and traverse commits")
    void testCreateAndTraverseTestRepository() throws IOException, GitAPIException {
        // Create a test Git repository in temp directory
        Path testRepoPath = tempDir.resolve("test-git-repo");
        Files.createDirectories(testRepoPath);
        
        // Initialize a new Git repository
        try (Git git = Git.init().setDirectory(testRepoPath.toFile()).call()) {
            Repository repository = git.getRepository();
            
            // Create a test file and make some commits
            Path testFile = testRepoPath.resolve("test.txt");
            
            // First commit
            Files.writeString(testFile, "First commit content");
            git.add().addFilepattern("test.txt").call();
            git.commit().setMessage("First commit").setAuthor("Test Author", "test@example.com").call();
            
            // Second commit
            Files.writeString(testFile, "Second commit content");
            git.add().addFilepattern("test.txt").call();
            git.commit().setMessage("Second commit").setAuthor("Test Author", "test@example.com").call();
            
            // Third commit
            Files.writeString(testFile, "Third commit content");
            git.add().addFilepattern("test.txt").call();
            git.commit().setMessage("Third commit").setAuthor("Test Author", "test@example.com").call();
            
            // Test getAllCommits
            List<RevCommit> commits = gitManager.getAllCommits(repository);
            
            assertNotNull(commits, "Commits list should not be null");
            assertEquals(3, commits.size(), "Should have exactly 3 commits");
            
            // Verify commits are in reverse chronological order (newest first)
            assertEquals("Third commit", commits.get(0).getShortMessage(),
                        "First commit in list should be the newest");
            assertEquals("Second commit", commits.get(1).getShortMessage(),
                        "Second commit in list should be the middle one");
            assertEquals("First commit", commits.get(2).getShortMessage(),
                        "Third commit in list should be the oldest");
            
            // Test repository info
            String repoInfo = gitManager.getRepositoryInfo(repository);
            assertNotNull(repoInfo, "Repository info should not be null");
            assertTrue(repoInfo.contains("Repository Information:"),
                      "Repository info should contain header");
            assertTrue(repoInfo.contains("Directory:"),
                      "Repository info should contain directory");
            assertTrue(repoInfo.contains("Third commit"),
                      "Repository info should contain HEAD commit message");
            
            // Test isValidGitRepository
            assertTrue(GitRepositoryManager.isValidGitRepository(testRepoPath),
                      "Should recognize valid Git repository");
            
            // Test close repository
            assertDoesNotThrow(() -> gitManager.closeRepository(repository),
                              "Should close repository without exception");
        }
    }

    @Test
    @DisplayName("Should handle repository with single commit")
    void testRepositoryWithSingleCommit() throws IOException, GitAPIException {
        Path singleCommitRepoPath = tempDir.resolve("single-commit-repo");
        Files.createDirectories(singleCommitRepoPath);
        
        try (Git git = Git.init().setDirectory(singleCommitRepoPath.toFile()).call()) {
            Repository repository = git.getRepository();
            
            // Create a test file and make one commit
            Path testFile = singleCommitRepoPath.resolve("single.txt");
            Files.writeString(testFile, "Single commit content");
            git.add().addFilepattern("single.txt").call();
            git.commit().setMessage("Only commit").setAuthor("Single Author", "single@example.com").call();
            
            // Test getAllCommits with single commit
            List<RevCommit> commits = gitManager.getAllCommits(repository);
            
            assertNotNull(commits, "Commits list should not be null");
            assertEquals(1, commits.size(), "Should have exactly 1 commit");
            assertEquals("Only commit", commits.get(0).getShortMessage(),
                        "Should have the correct commit message");
        }
    }

    @Test
    @DisplayName("Should handle configuration access during operations")
    void testConfigurationAccessDuringOperations() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::isLoggingVerbose).thenReturn(true);
            
            // Test that verbose logging configuration is accessed properly
            assertDoesNotThrow(() -> Config.isLoggingVerbose(),
                              "Should access verbose logging configuration");
            
            // Verify the configuration was called
            configMock.verify(() -> Config.isLoggingVerbose(), atLeastOnce());
        }
    }

    @Test
    @DisplayName("Should handle edge cases in repository operations")
    void testRepositoryOperationEdgeCases() throws IOException, GitAPIException {
        Path edgeCaseRepoPath = tempDir.resolve("edge-case-repo");
        Files.createDirectories(edgeCaseRepoPath);
        
        try (Git git = Git.init().setDirectory(edgeCaseRepoPath.toFile()).call()) {
            Repository repository = git.getRepository();
            
            // Test getAllCommits on empty repository (no commits yet)
            assertThrows(Exception.class, () -> gitManager.getAllCommits(repository),
                        "Should throw exception for repository with no commits");
        }
    }

    @Test
    @DisplayName("Should provide meaningful error messages")
    void testErrorMessages() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            // Test missing URL error message
            configMock.when(Config::getRepositoryUrl).thenReturn("");
            configMock.when(Config::getRepositoryLocalPath).thenReturn(testLocalPath);
            
            IllegalStateException urlException = assertThrows(IllegalStateException.class,
                () -> gitManager.loadOrCloneRepository());
            
            assertTrue(urlException.getMessage().contains("repository.url"),
                      "Error message should mention the configuration key");
            
            // Test missing local path error message
            configMock.when(Config::getRepositoryUrl).thenReturn(testRepoUrl);
            configMock.when(Config::getRepositoryLocalPath).thenReturn("");
            
            IllegalStateException pathException = assertThrows(IllegalStateException.class,
                () -> gitManager.loadOrCloneRepository());
            
            assertTrue(pathException.getMessage().contains("repository.local.path"),
                      "Error message should mention the configuration key");
        }
    }
}
