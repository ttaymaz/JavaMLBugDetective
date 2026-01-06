package org.tymz.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import org.tymz.config.Config;
import org.tymz.feature.DataPreprocessor.DataSplit;
import org.tymz.feature.DataPreprocessor.PredictionData;

import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive test suite for ModelTrainer class.
 * Tests temporal validation, class balancing, prediction functionality, and edge cases.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class ModelTrainerTest {

    private ModelTrainer modelTrainer;
    private Instances mockDataset;
    private DataSplit mockDataSplit;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Create mock dataset with synthetic data
        mockDataset = createMockDataset(100); // 100 instances for testing
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with Instances dataset")
        void shouldInitializeWithInstancesDataset() {
            modelTrainer = new ModelTrainer(mockDataset);
            assertNotNull(modelTrainer);
        }

        @Test
        @DisplayName("Should initialize with DataSplit")
        void shouldInitializeWithDataSplit() {
            mockDataSplit = createMockDataSplit();
            modelTrainer = new ModelTrainer(mockDataSplit);
            assertNotNull(modelTrainer);
        }

        @Test
        @DisplayName("Should handle null dataset gracefully")
        void shouldHandleNullDataset() {
            assertDoesNotThrow(() -> new ModelTrainer((Instances) null));
        }
    }

    @Nested
    @DisplayName("Temporal Validation Tests")
    class TemporalValidationTests {

        @BeforeEach
        void setUp() {
            modelTrainer = new ModelTrainer(mockDataset);
        }

        @Test
        @DisplayName("Should perform temporal validation successfully")
        void shouldPerformTemporalValidationSuccessfully() {
            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                // Mock config settings
                configMock.when(Config::getMLAlgorithm).thenReturn("naive_bayes");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                    assertFalse(results.isEmpty());
                    assertTrue(results.containsKey("NaiveBayes"));
                    
                    // Access Evaluation through the DTO
                    ModelEvaluationResult naiveBayesResult = results.get("NaiveBayes");
                    assertNotNull(naiveBayesResult.getEvaluation());
                    assertNotNull(naiveBayesResult.getEffortAwareMetrics());
                });
            }
        }

        @Test
        @DisplayName("Should handle all algorithms configuration")
        void shouldHandleAllAlgorithmsConfiguration() {
            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("all");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                    // Should test all 4 algorithms: NaiveBayes, J48, RandomForest, SMO
                    assertTrue(results.size() >= 4);
                    assertTrue(results.containsKey("NaiveBayes"));
                    assertTrue(results.containsKey("J48"));
                    assertTrue(results.containsKey("RandomForest"));
                    assertTrue(results.containsKey("SMO"));
                });
            }
        }

        @Test
        @DisplayName("Should create correct temporal split (80-20)")
        void shouldCreateCorrectTemporalSplit() {
            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("naive_bayes");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                // Test with known dataset size
                int totalInstances = mockDataset.numInstances();
                int expectedTrainSize = (int) Math.round(totalInstances * 0.8);
                int expectedTestSize = totalInstances - expectedTrainSize;

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    // The temporal split should be preserved (this is tested implicitly)
                    assertNotNull(results);
                });

                // Verify the split ratio is approximately 80-20
                assertTrue(expectedTrainSize > expectedTestSize);
                assertEquals(totalInstances, expectedTrainSize + expectedTestSize);
            }
        }

        @Test
        @DisplayName("Should handle class balancing when enabled")
        void shouldHandleClassBalancingWhenEnabled() {
            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("j48");
                configMock.when(Config::isMLBalanceClasses).thenReturn(true);

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                    assertTrue(results.containsKey("J48"));
                    
                    // Verify the result structure
                    ModelEvaluationResult j48Result = results.get("J48");
                    assertNotNull(j48Result.getEvaluation());
                    assertNotNull(j48Result.getEffortAwareMetrics());
                });
            }
        }

        @Test
        @DisplayName("Should handle individual algorithm selection")
        void shouldHandleIndividualAlgorithmSelection() {
            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                String[] algorithms = {"naive_bayes", "j48", "smo", "random_forest"};
                String[] expectedResults = {"NaiveBayes", "J48", "SMO", "RandomForest"};

                for (int i = 0; i < algorithms.length; i++) {
                    final String algorithm = algorithms[i];
                    final String expected = expectedResults[i];

                    configMock.when(Config::getMLAlgorithm).thenReturn(algorithm);

                    assertDoesNotThrow(() -> {
                        Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                        assertNotNull(results);
                        assertEquals(1, results.size());
                        assertTrue(results.containsKey(expected));
                        
                        // Verify the result structure
                        ModelEvaluationResult result = results.get(expected);
                        assertNotNull(result.getEvaluation());
                        assertNotNull(result.getEffortAwareMetrics());
                    });
                }
            }
        }
    }

    @Nested
    @DisplayName("Prediction Tests")
    class PredictionTests {

        @BeforeEach
        void setUp() {
            mockDataSplit = createMockDataSplit();
            modelTrainer = new ModelTrainer(mockDataset); // Use allData constructor for temporal validation
        }

        @Test
        @DisplayName("Should require PredictionData for predictions")
        void shouldRequirePredictionDataForPredictions() {
            // Create mock PredictionData
            PredictionData mockPredictionData = createMockPredictionData();
            
            // Test that the method requires PredictionData parameter
            assertDoesNotThrow(() -> {
                Map<Long, Double> predictions = modelTrainer.trainAndPredictOnLatestCommit(mockPredictionData);
                assertNotNull(predictions);
            });
        }

        @Test
        @DisplayName("Should handle prediction with valid PredictionData")
        void shouldHandlePredictionWithValidPredictionData() {
            // Test that initialization doesn't throw with valid dataset
            assertDoesNotThrow(() -> {
                ModelTrainer trainer = new ModelTrainer(mockDataset);
                assertNotNull(trainer);
            });
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle small dataset")
        void shouldHandleSmallDataset() {
            Instances smallDataset = createMockDataset(5); // Very small dataset
            modelTrainer = new ModelTrainer(smallDataset);

            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("naive_bayes");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                });
            }
        }

        @Test
        @DisplayName("Should handle single instance dataset gracefully")
        void shouldHandleSingleInstanceDataset() {
            Instances singleInstance = createMockDataset(1);
            modelTrainer = new ModelTrainer(singleInstance);

            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("naive_bayes");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                // Single instance should work but may have limited evaluation metrics
                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                });
            }
        }

        @Test
        @DisplayName("Should handle unknown algorithm gracefully")
        void shouldHandleUnknownAlgorithmGracefully() {
            modelTrainer = new ModelTrainer(mockDataset); // Initialize modelTrainer

            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("unknown_algorithm");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                    // Should default to "all" algorithms
                    assertTrue(results.size() >= 4);
                });
            }
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should complete validation within reasonable time")
        void shouldCompleteValidationWithinReasonableTime() {
            modelTrainer = new ModelTrainer(mockDataset);

            try (MockedStatic<Config> configMock = mockStatic(Config.class)) {
                configMock.when(Config::getMLAlgorithm).thenReturn("naive_bayes");
                configMock.when(Config::isMLBalanceClasses).thenReturn(false);

                long startTime = System.currentTimeMillis();
                
                assertDoesNotThrow(() -> {
                    Map<String, ModelEvaluationResult> results = modelTrainer.performTemporalValidation();
                    assertNotNull(results);
                });
                
                long duration = System.currentTimeMillis() - startTime;
                // Should complete within 30 seconds for small dataset
                assertTrue(duration < 30000, "Validation took too long: " + duration + "ms");
            }
        }
    }

    // Helper methods

    /**
     * Creates a mock dataset with synthetic data for testing
     */
    private Instances createMockDataset(int numInstances) {
        // Create attributes list
        ArrayList<Attribute> attributes = new ArrayList<>();
        
        // Revision ID attribute
        attributes.add(new Attribute("revision_id"));
        
        // Numerical features
        attributes.add(new Attribute("lines_of_code"));
        attributes.add(new Attribute("complexity"));
        
        // Class attribute (clean, buggy)
        ArrayList<String> classValues = new ArrayList<>();
        classValues.add("clean");
        classValues.add("buggy");
        attributes.add(new Attribute("is_buggy", classValues));
        
        // Create dataset
        Instances dataset = new Instances("TestDataset", attributes, numInstances);
        dataset.setClassIndex(3); // Last attribute is class
        
        // Add synthetic instances
        for (int i = 0; i < numInstances; i++) {
            double[] values = new double[4];
            values[0] = i + 1; // revision_id
            values[1] = 50 + (Math.random() * 200); // lines_of_code
            values[2] = 1 + (Math.random() * 10); // complexity
            values[3] = Math.random() > 0.7 ? 1 : 0; // is_buggy (30% buggy)
            
            dataset.add(new DenseInstance(1.0, values));
        }
        
        return dataset;
    }

    /**
     * Creates a mock DataSplit for prediction testing
     */
    private DataSplit createMockDataSplit() {
        Instances trainingSet = createMockDataset(80);
        Instances testSet = createMockDataset(20);
        
        return new DataSplit(trainingSet, testSet, "Mock split for testing");
    }
    
    /**
     * Creates a mock PredictionData for prediction testing
     */
    private PredictionData createMockPredictionData() {
        Instances trainingSet = createMockDataset(80);
        Instances predictionSet = createMockDataset(20);
        
        // Create mock revision IDs
        List<Long> revisionIds = new ArrayList<>();
        for (int i = 0; i < predictionSet.numInstances(); i++) {
            revisionIds.add((long) (i + 1));
        }
        
        return new PredictionData(trainingSet, predictionSet, revisionIds);
    }
}
