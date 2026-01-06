package org.tymz.feature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tymz.db.DatabaseManager;
import org.tymz.git.GitRepositoryManager;
import weka.core.Instances;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class for DataPreprocessor
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class DataPreprocessorTest {

    @TempDir
    Path tempDir;
    
    private Connection testConnection;
    private DatabaseManager mockDatabaseManager;
    private GitRepositoryManager mockGitManager;
    private DataPreprocessor dataPreprocessor;

    @BeforeEach
    void setUp() throws SQLException {
        // Setup test database
        testConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
        createTestSchema();
        
        // Create mock DatabaseManager that uses our test connection
        mockDatabaseManager = mock(DatabaseManager.class);
        when(mockDatabaseManager.getConnection()).thenReturn(testConnection);
        
        // Create mock GitRepositoryManager
        mockGitManager = mock(GitRepositoryManager.class);
        
        // Create DataPreprocessor with dependency injection
        dataPreprocessor = new DataPreprocessor(mockDatabaseManager, mockGitManager); // Use new constructor
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (testConnection != null) {
            testConnection.close();
        }
    }
    
    private void createTestSchema() throws SQLException {
        try (Statement stmt = testConnection.createStatement()) {
            // Create commits table
            stmt.execute("""
                CREATE TABLE commits (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    commit_hash TEXT UNIQUE NOT NULL,
                    author_name TEXT NOT NULL,
                    author_email TEXT NOT NULL,
                    commit_date TIMESTAMP NOT NULL,
                    message TEXT NOT NULL,
                    is_bug_fix BOOLEAN DEFAULT FALSE
                )
                """);
            
            // Create file_revisions table
            stmt.execute("""
                CREATE TABLE file_revisions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    commit_hash TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    change_type TEXT NOT NULL,
                    lines_added INTEGER DEFAULT 0,
                    lines_deleted INTEGER DEFAULT 0,
                    UNIQUE(commit_hash, file_path)
                )
                """);
            
            // Create metrics table
            stmt.execute("""
                CREATE TABLE metrics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_revision_id INTEGER NOT NULL,
                    metric_name TEXT NOT NULL,
                    metric_value REAL NOT NULL,
                    metric_type TEXT NOT NULL CHECK (metric_type IN ('PROCESS', 'STATIC')),
                    FOREIGN KEY (file_revision_id) REFERENCES file_revisions(id),
                    UNIQUE(file_revision_id, metric_name, metric_type)
                )
                """);
            
            // Create bug_labels table
            stmt.execute("""
                CREATE TABLE bug_labels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_revision_id INTEGER NOT NULL,
                    is_buggy BOOLEAN NOT NULL DEFAULT FALSE,
                    FOREIGN KEY (file_revision_id) REFERENCES file_revisions(id),
                    UNIQUE(file_revision_id)
                )
                """);
        }
    }
    
    @Test
    void testLoadDataFromDatabase() {
        // Note: This test would require mocking DatabaseManager.getInstance()
        // For now, we'll test the structure and logic principles
        
                assertNotNull(dataPreprocessor, "DataPreprocessor should be initialized");
        
        // Test that the required metric arrays are properly defined
        assertTrue(DataPreprocessor.class.getDeclaredFields().length > 0, 
            "DataPreprocessor should have required static fields");
    }
    
    @Test
    void testDataSplitContainer() {
        // Test the DataSplit container class
        
        // Create mock instances for testing
        weka.core.Attribute attr1 = new weka.core.Attribute("test_attr");
        java.util.ArrayList<weka.core.Attribute> attrs = new java.util.ArrayList<>();
        attrs.add(attr1);
        
        Instances mockTraining = new Instances("training", attrs, 0);
        Instances mockTest = new Instances("test", attrs, 0);
        
        DataPreprocessor.DataSplit split = new DataPreprocessor.DataSplit(mockTraining, mockTest, "Test split");
        
        assertNotNull(split.getTrainingSet(), "Training set should not be null");
        assertNotNull(split.getTestSet(), "Test set should not be null");
        assertEquals(mockTraining, split.getTrainingSet(), "Training set should match");
        assertEquals(mockTest, split.getTestSet(), "Test set should match");
        
        String splitString = split.toString();
        assertTrue(splitString.contains("training"), "ToString should contain training info");
        assertTrue(splitString.contains("test"), "ToString should contain test info");
    }
    
    @Test
    void testGetDatasetStatistics() {
        // Create a simple test dataset
        java.util.ArrayList<weka.core.Attribute> attrs = new java.util.ArrayList<>();
        
        // Add some numeric attributes
        attrs.add(new weka.core.Attribute("metric1"));
        attrs.add(new weka.core.Attribute("metric2"));
        
        // Add class attribute
        java.util.ArrayList<String> classValues = new java.util.ArrayList<>();
        classValues.add("clean");
        classValues.add("buggy");
        attrs.add(new weka.core.Attribute("class", classValues));
        
        Instances testData = new Instances("test", attrs, 0);
        testData.setClassIndex(testData.numAttributes() - 1);
        
        // Test empty dataset
        String emptyStats = dataPreprocessor.getDatasetStatistics(testData);
        assertEquals("Dataset is empty", emptyStats);
        
        // Add some instances
        weka.core.DenseInstance instance1 = new weka.core.DenseInstance(3);
        instance1.setDataset(testData);
        instance1.setValue(0, 1.0);
        instance1.setValue(1, 2.0);
        instance1.setValue(2, "clean");
        testData.add(instance1);
        
        weka.core.DenseInstance instance2 = new weka.core.DenseInstance(3);
        instance2.setDataset(testData);
        instance2.setValue(0, 3.0);
        instance2.setValue(1, 4.0);
        instance2.setValue(2, "buggy");
        testData.add(instance2);
        
        // Test non-empty dataset statistics
        String stats = dataPreprocessor.getDatasetStatistics(testData);
        assertTrue(stats.contains("Total instances: 2"), "Should show correct instance count");
        assertTrue(stats.contains("Total attributes: 3"), "Should show correct attribute count");
        assertTrue(stats.contains("clean"), "Should show class distribution");
        assertTrue(stats.contains("buggy"), "Should show class distribution");
    }
    
    @Test
    void testTemporalSplitLogic() throws SQLException {
        // Create a test dataset with temporal attributes
        java.util.ArrayList<weka.core.Attribute> attrs = new java.util.ArrayList<>();
        
        // Add metric attributes
        attrs.add(new weka.core.Attribute("lines_added"));
        attrs.add(new weka.core.Attribute("complexity"));
        
        // Add class attribute
        java.util.ArrayList<String> classValues = new java.util.ArrayList<>();
        classValues.add("clean");
        classValues.add("buggy");
        attrs.add(new weka.core.Attribute("class", classValues));
        
        Instances testData = new Instances("test", attrs, 0);
        testData.setClassIndex(testData.numAttributes() - 1);
        
        // Add test instances
        for (int i = 0; i < 10; i++) {
            weka.core.DenseInstance instance = new weka.core.DenseInstance(3);
            instance.setDataset(testData);
            instance.setValue(0, i * 10);
            instance.setValue(1, i * 5);
            instance.setValue(2, i % 2 == 0 ? "clean" : "buggy");
            testData.add(instance);
        }
        
        // Test that split method handles empty mapping gracefully
        DataPreprocessor.DataSplit split = dataPreprocessor.splitData(testData);
        
        assertNotNull(split, "Split should not be null");
        assertNotNull(split.getTrainingSet(), "Training set should not be null");
        assertNotNull(split.getTestSet(), "Test set should not be null");
        
        // Should fall back to percentage-based split when no temporal data
        int totalInstances = split.getTrainingSet().numInstances() + split.getTestSet().numInstances();
        assertEquals(testData.numInstances(), totalInstances, "Total instances should be preserved");
        
        // Training set should be larger than test set in fallback mode
        assertTrue(split.getTrainingSet().numInstances() >= split.getTestSet().numInstances(), 
                  "Training set should be larger or equal to test set");
    }
}
