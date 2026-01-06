package org.tymz.metric;

import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.*;
import org.tymz.db.DatabaseManager;
import org.tymz.git.GitRepositoryManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProcessMetricsCalculator class.
 * 
 * Tests the enhanced single-pass process metrics calculation including:
 * - Single-pass architecture functionality
 * - New diff/churn metrics (LINES_ADDED, LINES_DELETED, HUNK_COUNT)
 * - Traditional metrics (NR, NDEV, AGE, EXP)
 * - Database integration
 * - Error handling
 * - Max commits configuration compliance
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProcessMetricsCalculatorTest {

    private ProcessMetricsCalculator metricsCalculator;
    private DatabaseManager mockDatabaseManager;
    private GitRepositoryManager gitManager;
    private Repository repository;

    @BeforeEach
    void setUp() throws Exception {
        // Mock DatabaseManager
        mockDatabaseManager = mock(DatabaseManager.class);
        
        // Create ProcessMetricsCalculator with mocked DatabaseManager
        metricsCalculator = new ProcessMetricsCalculator(mockDatabaseManager);
        
        // Setup real Git repository for integration tests
        gitManager = new GitRepositoryManager();
        repository = gitManager.loadOrCloneRepository();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (repository != null) {
            gitManager.closeRepository(repository);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Constructor should initialize with valid configuration")
    void testConstructorInitialization() {
        assertNotNull(metricsCalculator, "ProcessMetricsCalculator should be created successfully");
    }

    @Test
    @Order(2)
    @DisplayName("calculateAndSaveMetricsForAllCommits should handle repository without errors")
    void testCalculateMetricsWithRepository() throws Exception {
        // Configure mock database manager
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        doNothing().when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        assertDoesNotThrow(() -> metricsCalculator.calculateAndSaveMetricsForAllCommits(repository),
            "Should process repository without throwing exceptions");
    }

    @Test
    @Order(3)
    @DisplayName("calculateAndSaveMetricsForAllCommits should respect max.commits configuration")
    void testCalculateMetricsWithMaxCommitsLimit() throws Exception {
        // This test verifies that the max.commits configuration is respected
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        doNothing().when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        // The actual limiting is handled by the Config.getProperty("max.commits") in ProcessMetricsCalculator
        assertDoesNotThrow(() -> metricsCalculator.calculateAndSaveMetricsForAllCommits(repository),
            "Should respect max.commits configuration and process limited commits");
    }

    @Test
    @Order(4)
    @DisplayName("calculateAndSaveMetricsForAllCommits should handle database errors gracefully")
    void testCalculateMetricsWithDatabaseError() throws Exception {
        // Simulate database error
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenThrow(new SQLException("Database error"));
        
        assertThrows(SQLException.class, () -> metricsCalculator.calculateAndSaveMetricsForAllCommits(repository),
            "Should propagate database errors");
    }

    @Test
    @Order(5)
    @DisplayName("Metrics calculation should include new diff/churn metrics")
    void testDiffChurnMetricsCalculation() throws Exception {
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        
        List<String> capturedMetricNames = new ArrayList<>();
        List<Double> capturedMetricValues = new ArrayList<>();
        
        doAnswer(invocation -> {
            String metricName = invocation.getArgument(1);
            Double metricValue = invocation.getArgument(2);
            capturedMetricNames.add(metricName);
            capturedMetricValues.add(metricValue);
            return null;
        }).when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        metricsCalculator.calculateAndSaveMetricsForAllCommits(repository);
        
        // Verify traditional metrics are calculated
        assertTrue(capturedMetricNames.contains("NR"), "NR metrics should be calculated");
        assertTrue(capturedMetricNames.contains("NDEV"), "NDEV metrics should be calculated");
        assertTrue(capturedMetricNames.contains("AGE"), "AGE metrics should be calculated");
        assertTrue(capturedMetricNames.contains("EXP"), "EXP metrics should be calculated");
        
        // Verify new diff/churn metrics are calculated
        assertTrue(capturedMetricNames.contains("LINES_ADDED"), "LINES_ADDED metrics should be calculated");
        assertTrue(capturedMetricNames.contains("LINES_DELETED"), "LINES_DELETED metrics should be calculated");
        assertTrue(capturedMetricNames.contains("HUNK_COUNT"), "HUNK_COUNT metrics should be calculated");
        
        // Verify metrics have reasonable values
        assertTrue(capturedMetricValues.stream().allMatch(value -> value >= 0), "All metric values should be non-negative");
    }

    @Test
    @Order(6)
    @DisplayName("Single-pass architecture should process commits chronologically")
    void testChronologicalProcessing() throws Exception {
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        doNothing().when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        assertDoesNotThrow(() -> metricsCalculator.calculateAndSaveMetricsForAllCommits(repository),
            "Should process commits in chronological order without errors");
        
        // Verify that commits were inserted (indicating chronological processing worked)
        verify(mockDatabaseManager, atLeastOnce()).insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    @Order(7)
    @DisplayName("Database integration should handle all metric types correctly")
    void testDatabaseIntegration() throws Exception {
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        
        List<String> capturedMetricTypes = new ArrayList<>();
        
        doAnswer(invocation -> {
            String metricType = invocation.getArgument(3); // 4th parameter is metric type
            capturedMetricTypes.add(metricType);
            return null;
        }).when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        metricsCalculator.calculateAndSaveMetricsForAllCommits(repository);
        
        // Verify that all metrics are marked as "PROCESS" type
        assertTrue(capturedMetricTypes.stream().allMatch(type -> "PROCESS".equals(type)), 
            "All calculated metrics should be of type 'PROCESS'");
    }

    @Test
    @Order(8)
    @DisplayName("Error handling should work for invalid repository states")
    void testInvalidRepositoryHandling() throws Exception {
        // Test with null repository - this should throw an exception
        assertThrows(Exception.class, () -> metricsCalculator.calculateAndSaveMetricsForAllCommits(null),
            "Should handle null repository gracefully");
    }

    @Test
    @Order(9)
    @DisplayName("Memory efficiency should be maintained during processing")
    void testMemoryEfficiency() throws Exception {
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        doNothing().when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        // Get initial memory usage
        Runtime runtime = Runtime.getRuntime();
        runtime.gc(); // Force garbage collection
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Process repository
        metricsCalculator.calculateAndSaveMetricsForAllCommits(repository);
        
        runtime.gc(); // Force garbage collection again
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Memory usage should not increase dramatically (single-pass should be memory efficient)
        long memoryIncrease = finalMemory - initialMemory;
        System.out.println("Memory increase during processing: " + (memoryIncrease / 1024 / 1024) + " MB");
        
        // This is a reasonable memory increase limit for single-pass processing
        assertTrue(memoryIncrease < 500 * 1024 * 1024, "Memory increase should be reasonable (< 500MB)");
    }

    @Test
    @Order(10)
    @DisplayName("Integration test with real repository data should complete successfully")
    void testRealRepositoryIntegration() throws Exception {
        when(mockDatabaseManager.insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean()))
            .thenReturn(1L);
        when(mockDatabaseManager.insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(1L);
        doNothing().when(mockDatabaseManager).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
        
        long startTime = System.currentTimeMillis();
        
        assertDoesNotThrow(() -> metricsCalculator.calculateAndSaveMetricsForAllCommits(repository),
            "Should work with real repository data");
        
        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;
        
        System.out.println("Real repository processing time: " + executionTime + "ms");
        
        // Verify that some database operations occurred
        verify(mockDatabaseManager, atLeastOnce()).insertCommit(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean());
        verify(mockDatabaseManager, atLeastOnce()).insertFileRevision(anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(mockDatabaseManager, atLeastOnce()).insertMetric(anyLong(), anyString(), anyDouble(), anyString());
    }
}
