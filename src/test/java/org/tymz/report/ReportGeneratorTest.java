package org.tymz.report;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.tymz.ml.ModelEvaluationResult;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ReportGenerator with cross-validation functionality
 * 
 * Tests the updated ReportGenerator that works with cross-validation results
 * instead of train-test splits.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
@DisplayName("ReportGenerator Tests")
class ReportGeneratorTest {

    private Instances allData;
    private Map<String, ModelEvaluationResult> mockModelResults;
    private String repoName;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        setupMockData();
        repoName = "test-repo";
    }

    private void setupMockData() throws Exception {
        // Create mock dataset with attributes (same as DataPreprocessor)
        ArrayList<Attribute> attributes = new ArrayList<>();
        
        // Process metrics
        attributes.add(new Attribute("NR"));
        attributes.add(new Attribute("NDEV"));
        attributes.add(new Attribute("AGE"));
        attributes.add(new Attribute("EXP"));
        
        // Static metrics
        attributes.add(new Attribute("WEIGHED_METHOD_COUNT"));
        attributes.add(new Attribute("TIGHT_CLASS_COHESION"));
        attributes.add(new Attribute("NCSS"));
        attributes.add(new Attribute("LINES_OF_CODE"));
        
        // Class attribute
        ArrayList<String> classValues = new ArrayList<>();
        classValues.add("clean");
        classValues.add("buggy");
        attributes.add(new Attribute("is_buggy", classValues));

        allData = new Instances("TestDataset", attributes, 0);
        allData.setClassIndex(allData.numAttributes() - 1);
        
        // Add sample instances
        addSampleInstances();
        
        // Create mock evaluation results
        setupMockEvaluationResults();
    }

    private void addSampleInstances() {
        // Add clean instances
        for (int i = 0; i < 5; i++) {
            addInstance(new double[]{2.0, 1.0, 10.0, 5.0, 3.0, 0.8, 50.0, 45.0}, "clean");
        }
        
        // Add buggy instances
        for (int i = 0; i < 15; i++) {
            addInstance(new double[]{8.0, 4.0, 50.0, 25.0, 15.0, 0.3, 200.0, 180.0}, "buggy");
        }
    }

    private void addInstance(double[] values, String classValue) {
        Instance instance = new DenseInstance(values.length + 1);
        instance.setDataset(allData);
        
        // Set attribute values
        for (int i = 0; i < values.length; i++) {
            instance.setValue(i, values[i]);
        }
        
        // Set class value
        instance.setValue(allData.classIndex(), classValue);
        allData.add(instance);
    }

    private void setupMockEvaluationResults() throws Exception {
        mockModelResults = new HashMap<>();
        
        // Create mock evaluations for each model
        String[] modelNames = {"NaiveBayes", "J48", "RandomForest", "SMO"};
        
        for (String modelName : modelNames) {
            Evaluation eval = new Evaluation(allData);
            
            // Build a simple classifier and evaluate it to get realistic metrics
            NaiveBayes classifier = new NaiveBayes();
            classifier.buildClassifier(allData);
            eval.evaluateModel(classifier, allData);
            
            // Create dummy effort metrics
            Map<String, Double> dummyEffortMetrics = new HashMap<>();
            dummyEffortMetrics.put("Popt", 0.25);
            dummyEffortMetrics.put("Inspection_Rate", 0.20);
            
            // Wrap in ModelEvaluationResult DTO
            ModelEvaluationResult result = new ModelEvaluationResult(eval, dummyEffortMetrics);
            mockModelResults.put(modelName, result);
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        
        @Test
        @DisplayName("Should accept valid parameters")
        void shouldAcceptValidParameters() {
            assertDoesNotThrow(() -> 
                new ReportGenerator(mockModelResults, allData, repoName)
            );
        }
        
        @Test
        @DisplayName("Should handle empty model results")
        void shouldHandleEmptyModelResults() {
            Map<String, ModelEvaluationResult> emptyResults = new HashMap<>();
            assertDoesNotThrow(() -> 
                new ReportGenerator(emptyResults, allData, repoName)
            );
        }
        
        @Test
        @DisplayName("Should handle different repository names")
        void shouldHandleDifferentRepositoryNames() {
            assertDoesNotThrow(() -> 
                new ReportGenerator(mockModelResults, allData, "different-repo")
            );
            
            assertDoesNotThrow(() -> 
                new ReportGenerator(mockModelResults, allData, "repo_with_underscores")
            );
        }
    }

    @Nested
    @DisplayName("Report Generation Tests")
    class ReportGenerationTests {
        
        @Test
        @DisplayName("Should generate report successfully")
        void shouldGenerateReportSuccessfully() {
            ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
            
            assertDoesNotThrow(() -> {
                String filename = generator.generateReport();
                assertNotNull(filename);
                assertFalse(filename.isEmpty());
            });
        }
        
        @Test
        @DisplayName("Should create report file with dynamic name")
        void shouldCreateReportFileWithDynamicName() throws IOException {
            ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
            String filename = generator.generateReport();
            
            // Check that file was created in reports directory
            Path reportPath = Paths.get(filename);
            assertTrue(Files.exists(reportPath), "Report file should exist: " + filename);
            
            // Check filename format
            String fileName = reportPath.getFileName().toString();
            assertTrue(fileName.startsWith(repoName + "-report-"), "Filename should start with repo name");
            assertTrue(fileName.endsWith(".md"), "Filename should end with .md");
            
            // Cleanup
            Files.deleteIfExists(reportPath);
        }
        
        @Test
        @DisplayName("Should include timestamp in filename")
        void shouldIncludeTimestampInFilename() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename1 = generator.generateReport();
                
                // Small delay to ensure different timestamp
                Thread.sleep(1000);
                
                String filename2 = generator.generateReport();
                
                // Filenames should be different due to timestamp
                assertNotEquals(filename1, filename2);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Test interrupted");
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    @Nested
    @DisplayName("Report Content Tests")
    class ReportContentTests {
        
        @Test
        @DisplayName("Should include dataset overview section")
        void shouldIncludeDatasetOverviewSection() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("## Dataset Overview"));
                assertTrue(content.contains("Total Instances"));
                assertTrue(content.contains("Number of Features"));
                assertTrue(content.contains("10-Fold Cross-Validation"));
                assertTrue(content.contains("SMOTE"));
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include model performance section")
        void shouldIncludeModelPerformanceSection() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("## Model Performance"));
                
                // Should include sections for each model
                for (String modelName : mockModelResults.keySet()) {
                    assertTrue(content.contains("### " + modelName));
                }
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include cross-validation metrics")
        void shouldIncludeCrossValidationMetrics() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("Cross-Validation Performance Metrics"));
                assertTrue(content.contains("Accuracy"));
                assertTrue(content.contains("Precision (buggy)"));
                assertTrue(content.contains("Recall (buggy)"));
                assertTrue(content.contains("F1-Score (buggy)"));
                assertTrue(content.contains("AUC-ROC"));
                assertTrue(content.contains("Kappa Statistic"));
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include confusion matrices")
        void shouldIncludeConfusionMatrices() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("Confusion Matrix"));
                assertTrue(content.contains("Predicted"));
                assertTrue(content.contains("clean"));
                assertTrue(content.contains("buggy"));
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include updated conclusion section")
        void shouldIncludeUpdatedConclusionSection() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("## Conclusion & Key Findings"));
                assertTrue(content.contains("Analysis Summary"));
                assertTrue(content.contains("Cost-Sensitive Classification"));
                assertTrue(content.contains("Key Achievements"));
                assertTrue(content.contains("Best Performing Model"));
                assertTrue(content.contains("Recommendations"));
                assertTrue(content.contains("Technical Excellence"));
                
                // Should not contain old problematic content
                assertFalse(content.contains("Critical Issues Identified"));
                assertFalse(content.contains("Test Set Class Imbalance"));
                assertFalse(content.contains("Misleading Accuracy Scores"));
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include class distribution")
        void shouldIncludeClassDistribution() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("Class Distribution"));
                assertTrue(content.contains("clean"));
                assertTrue(content.contains("buggy"));
                assertTrue(content.contains("25,0%")); // 5 out of 20 instances
                assertTrue(content.contains("75,0%")); // 15 out of 20 instances
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
        
        @Test
        @DisplayName("Should include header information")
        void shouldIncludeHeaderInformation() throws IOException {
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(mockModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                
                assertTrue(content.contains("# Bug Prediction Analysis Report"));
                assertTrue(content.contains("Generated on:"));
                assertTrue(content.contains("JavaMLBugDetective"));
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {
        
        @Test
        @DisplayName("Should handle repository name with special characters")
        void shouldHandleRepositoryNameWithSpecialCharacters() throws IOException {
            String specialRepoName = "repo-with-special.chars_123";
            
            ReportGenerator generator = new ReportGenerator(mockModelResults, allData, specialRepoName);
            String filename = generator.generateReport();
            
            // Check that file was created with special characters handled
            Path reportPath = Paths.get(filename);
            assertTrue(Files.exists(reportPath), "Report file should exist: " + filename);
            
            String fileName = reportPath.getFileName().toString();
            assertTrue(fileName.startsWith(specialRepoName + "-report-"), "Filename should start with special repo name");
            assertTrue(fileName.endsWith(".md"), "Filename should end with .md");
            
            // Cleanup
            Files.deleteIfExists(reportPath);
        }
        
        @Test
        @DisplayName("Should handle single model result")
        void shouldHandleSingleModelResult() throws IOException {
            Map<String, ModelEvaluationResult> singleModelResults = new HashMap<>();
            singleModelResults.put("NaiveBayes", mockModelResults.get("NaiveBayes"));
            
            String originalDir = System.getProperty("user.dir");
            System.setProperty("user.dir", tempDir.toString());
            
            try {
                ReportGenerator generator = new ReportGenerator(singleModelResults, allData, repoName);
                String filename = generator.generateReport();
                
                String content = Files.readString(Paths.get(filename));
                assertTrue(content.contains("### NaiveBayes"));
                assertEquals(1, singleModelResults.size());
                
            } finally {
                System.setProperty("user.dir", originalDir);
            }
        }
    }
}
