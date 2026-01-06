package org.tymz.db;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.tymz.config.Config;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the DatabaseManager class.
 * Tests database initialization, CRUD operations, and transaction management.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class DatabaseManagerTest {

    @TempDir
    Path tempDir;
    
    private DatabaseManager dbManager;
    private String testDbPath;

    @BeforeEach
    void setUp() {
        // Create a temporary database file for testing
        testDbPath = tempDir.resolve("test_db.db").toString();
        
        // Mock the Config class to return our test database path
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::getDatabaseName).thenReturn(testDbPath);
            configMock.when(Config::isLoggingVerbose).thenReturn(false);
            
            // Get a fresh instance for each test
            dbManager = DatabaseManager.getInstance();
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (dbManager != null) {
            dbManager.close();
        }
        
        // Clean up the test database file
        File dbFile = new File(testDbPath);
        if (dbFile.exists()) {
            dbFile.delete();
        }
        
        // Reset the singleton instance using reflection
        try {
            java.lang.reflect.Field instance = DatabaseManager.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore reflection errors in tests
        }
    }

    @Test
    @DisplayName("Should create database instance successfully")
    void testDatabaseInstanceCreation() {
        assertNotNull(dbManager, "DatabaseManager instance should not be null");
        assertTrue(new File(testDbPath).exists(), "Database file should be created");
    }

    @Test
    @DisplayName("Should implement singleton pattern correctly")
    void testSingletonPattern() {
        try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
            configMock.when(Config::getDatabaseName).thenReturn(testDbPath);
            configMock.when(Config::isLoggingVerbose).thenReturn(false);
            
            DatabaseManager instance1 = DatabaseManager.getInstance();
            DatabaseManager instance2 = DatabaseManager.getInstance();
            
            assertSame(instance1, instance2, "Should return the same instance");
        }
    }

    @Test
    @DisplayName("Should initialize schema successfully")
    void testSchemaInitialization() throws SQLException {
        // Schema should be initialized during construction
        assertDoesNotThrow(() -> dbManager.initializeSchema(), 
                          "Schema initialization should not throw exception");
        
        // Verify tables exist by trying to query them
        assertDoesNotThrow(() -> dbManager.getCommitCount(), 
                          "Should be able to query commits table");
        assertDoesNotThrow(() -> dbManager.getFileRevisionCount(), 
                          "Should be able to query file_revisions table");
    }

    @Test
    @DisplayName("Should insert and retrieve commit successfully")
    void testInsertCommit() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        String commitHash = "abc123def456";
        String authorName = "John Doe";
        String authorEmail = "john.doe@example.com";
        String message = "Fix critical bug in authentication";
        
        long commitId = dbManager.insertCommit(commitHash, authorName, authorEmail, now, message, true);
        
        assertTrue(commitId > 0, "Commit ID should be positive");
        assertTrue(dbManager.commitExists(commitHash), "Commit should exist in database");
        assertEquals(commitId, dbManager.getCommitId(commitHash), "Should retrieve correct commit ID");
        assertEquals(1, dbManager.getCommitCount(), "Should have one commit in database");
    }

    @Test
    @DisplayName("Should handle duplicate commit insertion gracefully")
    void testDuplicateCommitInsertion() throws SQLException {
        LocalDateTime now = LocalDateTime.now();
        String commitHash = "duplicate123";
        String authorName = "Jane Doe";
        String authorEmail = "jane.doe@example.com";
        String message = "Initial commit";
        
        long firstId = dbManager.insertCommit(commitHash, authorName, authorEmail, now, message, false);
        long secondId = dbManager.insertCommit(commitHash, authorName, authorEmail, now, message, false);
        
        assertEquals(firstId, secondId, "Duplicate insertion should return same ID");
        assertEquals(1, dbManager.getCommitCount(), "Should still have only one commit");
    }

    @Test
    @DisplayName("Should insert and retrieve file revision successfully")
    void testInsertFileRevision() throws SQLException {
        // First insert a commit
        String commitHash = "commit123";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        String filePath = "src/main/java/Test.java";
        String changeType = "MODIFY";
        int linesAdded = 10;
        int linesDeleted = 5;
        
        long revisionId = dbManager.insertFileRevision(filePath, commitHash, changeType, 
                                                      linesAdded, linesDeleted);
        
        assertTrue(revisionId > 0, "File revision ID should be positive");
        assertTrue(dbManager.fileRevisionExists(filePath, commitHash), 
                  "File revision should exist in database");
        assertEquals(revisionId, dbManager.getFileRevisionId(filePath, commitHash), 
                    "Should retrieve correct file revision ID");
        assertEquals(1, dbManager.getFileRevisionCount(), "Should have one file revision in database");
    }

    @Test
    @DisplayName("Should insert metrics successfully")
    void testInsertMetric() throws SQLException {
        // Setup prerequisite data
        String commitHash = "metric_commit";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        long revisionId = dbManager.insertFileRevision("TestFile.java", commitHash, "ADD", 50, 0);
        
        // Insert metrics
        assertDoesNotThrow(() -> dbManager.insertMetric(revisionId, "LOC", 100.0, "STATIC"), 
                          "Should insert static metric successfully");
        assertDoesNotThrow(() -> dbManager.insertMetric(revisionId, "NR", 5.0, "PROCESS"), 
                          "Should insert process metric successfully");
        assertDoesNotThrow(() -> dbManager.insertMetric(revisionId, "CYCLOMATIC_COMPLEXITY", 3.5, "STATIC"), 
                          "Should insert complexity metric successfully");
    }

    @Test
    @DisplayName("Should insert bug labels successfully")
    void testInsertBugLabel() throws SQLException {
        // Setup prerequisite data
        String commitHash = "bug_commit";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        long revisionId = dbManager.insertFileRevision("BuggyFile.java", commitHash, "MODIFY", 20, 10);
        
        // Insert bug label
        assertDoesNotThrow(() -> dbManager.insertBugLabel(revisionId, true, 0.85, "SZZ_ALGORITHM"), 
                          "Should insert bug label successfully");
        
        // Test update existing label
        assertDoesNotThrow(() -> dbManager.insertBugLabel(revisionId, false, 0.95, "MANUAL_REVIEW"), 
                          "Should update existing bug label successfully");
    }

    @Test
    @DisplayName("Should validate change type constraints")
    void testChangeTypeValidation() throws SQLException {
        String commitHash = "constraint_test";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        // Valid change types should work
        assertDoesNotThrow(() -> dbManager.insertFileRevision("File1.java", commitHash, "ADD", 10, 0));
        assertDoesNotThrow(() -> dbManager.insertFileRevision("File2.java", commitHash, "MODIFY", 5, 3));
        assertDoesNotThrow(() -> dbManager.insertFileRevision("File3.java", commitHash, "DELETE", 0, 20));
        assertDoesNotThrow(() -> dbManager.insertFileRevision("File4.java", commitHash, "RENAME", 0, 0));
        
        // Invalid change type should fail
        assertThrows(SQLException.class, () -> 
            dbManager.insertFileRevision("File5.java", commitHash, "INVALID", 0, 0),
            "Should reject invalid change type");
    }

    @Test
    @DisplayName("Should validate metric type constraints")
    void testMetricTypeValidation() throws SQLException {
        // Setup prerequisite data
        String commitHash = "metric_validation";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        long revisionId = dbManager.insertFileRevision("TestFile.java", commitHash, "ADD", 10, 0);
        
        // Valid metric types should work
        assertDoesNotThrow(() -> dbManager.insertMetric(revisionId, "TEST_STATIC", 1.0, "STATIC"));
        assertDoesNotThrow(() -> dbManager.insertMetric(revisionId, "TEST_PROCESS", 2.0, "PROCESS"));
        
        // Invalid metric type should fail
        assertThrows(SQLException.class, () -> 
            dbManager.insertMetric(revisionId, "TEST_INVALID", 3.0, "INVALID"),
            "Should reject invalid metric type");
    }

    @Test
    @DisplayName("Should retrieve all file paths correctly")
    void testGetAllFilePaths() throws SQLException {
        String commitHash = "paths_test";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        // Insert multiple file revisions
        dbManager.insertFileRevision("src/main/java/Class1.java", commitHash, "ADD", 50, 0);
        dbManager.insertFileRevision("src/main/java/Class2.java", commitHash, "ADD", 30, 0);
        dbManager.insertFileRevision("src/test/java/Test1.java", commitHash, "ADD", 20, 0);
        
        List<String> filePaths = dbManager.getAllFilePaths();
        
        assertEquals(3, filePaths.size(), "Should have 3 unique file paths");
        assertTrue(filePaths.contains("src/main/java/Class1.java"), "Should contain Class1.java");
        assertTrue(filePaths.contains("src/main/java/Class2.java"), "Should contain Class2.java");
        assertTrue(filePaths.contains("src/test/java/Test1.java"), "Should contain Test1.java");
    }

    @Test
    @DisplayName("Should handle transaction management correctly")
    void testTransactionManagement() throws SQLException {
        String commitHash = "transaction_test";
        
        // Test successful transaction
        dbManager.beginTransaction();
        try {
            dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                                  LocalDateTime.now(), "Test commit", false);
            dbManager.commit();
            
            assertTrue(dbManager.commitExists(commitHash), "Commit should exist after successful transaction");
        } catch (SQLException e) {
            dbManager.rollback();
            fail("Transaction should not fail");
        } finally {
            dbManager.endTransaction();
        }
        
        // Test rollback
        String failedCommitHash = "failed_commit";
        dbManager.beginTransaction();
        try {
            dbManager.insertCommit(failedCommitHash, "Author", "author@test.com", 
                                  LocalDateTime.now(), "Failed commit", false);
            // Simulate an error and rollback
            dbManager.rollback();
            
            assertFalse(dbManager.commitExists(failedCommitHash), 
                       "Commit should not exist after rollback");
        } finally {
            dbManager.endTransaction();
        }
    }

    @Test
    @DisplayName("Should generate database statistics correctly")
    void testDatabaseStats() throws SQLException {
        // Insert some test data
        String commitHash = "stats_test";
        dbManager.insertCommit(commitHash, "Author", "author@test.com", 
                              LocalDateTime.now(), "Test commit", false);
        
        long revisionId = dbManager.insertFileRevision("StatsFile.java", commitHash, "ADD", 100, 0);
        dbManager.insertMetric(revisionId, "LOC", 100.0, "STATIC");
        dbManager.insertBugLabel(revisionId, false, 1.0, "TEST");
        
        String stats = dbManager.getDatabaseStats();
        
        assertNotNull(stats, "Statistics should not be null");
        assertTrue(stats.contains("Total Commits: 1"), "Should show correct commit count");
        assertTrue(stats.contains("Total File Revisions: 1"), "Should show correct file revision count");
        assertTrue(stats.contains("Total Metrics: 1"), "Should show correct metrics count");
        assertTrue(stats.contains("Total Bug Labels: 1"), "Should show correct bug labels count");
    }

    @Test
    @DisplayName("Should close database connection properly")
    void testDatabaseClose() throws SQLException {
        // Get the connection first to ensure it exists
        Connection conn = dbManager.getConnection();
        assertFalse(conn.isClosed(), "Connection should be open initially");
        
        // Close the database manager
        assertDoesNotThrow(() -> dbManager.close(), "Should close database without exception");
        
        // The original connection should be closed
        assertTrue(conn.isClosed(), "Original connection should be closed");
        
        // But getting a new connection should work (auto-recovery)
        Connection newConn = dbManager.getConnection();
        assertFalse(newConn.isClosed(), "New connection should be open after auto-recovery");
    }

    @Test
    @DisplayName("Should handle foreign key constraints")
    void testForeignKeyConstraints() throws SQLException {
        // Try to insert file revision without corresponding commit
        assertThrows(SQLException.class, () -> 
            dbManager.insertFileRevision("test.java", "nonexistent_commit", "ADD", 10, 0),
            "Should enforce foreign key constraint for commit_hash");
        
        // Try to insert metric without corresponding file revision
        assertThrows(SQLException.class, () -> 
            dbManager.insertMetric(999999L, "TEST_METRIC", 1.0, "STATIC"),
            "Should enforce foreign key constraint for file_revision_id");
        
        // Try to insert bug label without corresponding file revision
        assertThrows(SQLException.class, () -> 
            dbManager.insertBugLabel(999999L, true, 1.0, "TEST"),
            "Should enforce foreign key constraint for file_revision_id");
    }
}
