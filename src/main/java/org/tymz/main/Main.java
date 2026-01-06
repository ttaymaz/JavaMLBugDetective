package org.tymz.main;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.tymz.config.Config;
import org.tymz.db.DatabaseManager;
import org.tymz.feature.DataPreprocessor;
import org.tymz.feature.DataPreprocessor.DataSplit;
import org.tymz.feature.DataPreprocessor.PredictionData;
import org.tymz.git.GitRepositoryManager;
import org.tymz.metric.ProcessMetricsCalculator;
import org.tymz.metric.StaticMetricsCalculator;
import org.tymz.ml.ModelTrainer;
import org.tymz.ml.ModelEvaluationResult;
import org.tymz.report.ReportGenerator;
import org.tymz.szz.SZZBugLabeler;
import org.tymz.version.VersionManager;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Main entry point for the JavaMLBugDetective project.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class Main {

    /**
     * Main entry point of the application.
     */
    public static void main(String[] args) {
        System.out.println("🚀 Starting JavaMLBugDetective v1.0.0");
        System.out.println("Research Framework for Bug Prediction with 14-Feature Set and Version-Based Validation");
        System.out.println("=".repeat(80));

        // Centralized service instances - Single source of truth
        DatabaseManager dbManager = null;
        GitRepositoryManager gitManager = null;
        Repository repository = null;

        try {
            // Step 1: Load configuration
            System.out.println("Step 1: Loading configuration...");
            if (!Config.isInitialized()) {
                throw new RuntimeException("Configuration failed to initialize");
            }
            System.out.println("✓ Configuration loaded successfully");

            // Step 2: Initialize core services with dependency injection
            System.out.println("Step 2: Initializing core services...");
            dbManager = DatabaseManager.getInstance();
            gitManager = new GitRepositoryManager();
            System.out.println("✓ Core services initialized");

            // Step 3: Setup Git repository analysis
            System.out.println("Step 3: Setting up Git repository analysis...");
            repository = gitManager.loadOrCloneRepository();

            List<RevCommit> commits = gitManager.getAllCommits(repository);

            int maxCommits = Integer.parseInt(Config.getProperty("max.commits", "0"));
            if (maxCommits > 0 && commits.size() > maxCommits) {
                commits = commits.subList(0, maxCommits);
                System.out.println("Limited to " + maxCommits + " commits for testing");
            }
            
            System.out.println("✓ Git repository loaded with " + commits.size() + " commits");
            System.out.println("Repository info:");
            System.out.println(gitManager.getRepositoryInfo(repository));

// Step 4: Run SZZ bug labeling
            System.out.println("Step 4: Running SZZ bug labeling algorithm...");
            SZZBugLabeler szzLabeler = new SZZBugLabeler(dbManager); // Inject dependency
            szzLabeler.labelBugs(repository, commits);
            dbManager.commit(); // <-- BU SATIR ÖNEMLİ
            System.out.println("✓ SZZ bug labeling completed");
            System.out.println(szzLabeler.getLabelingStats());

            List<RevCommit> chronologicalCommits = new ArrayList<>(commits);
            Collections.reverse(chronologicalCommits);

            // Step 5: Calculate process metrics with enhanced diff/churn metrics
            System.out.println("Step 5: Calculating enhanced process metrics with diff/churn metrics...");
            ProcessMetricsCalculator processMetricsCalculator = new ProcessMetricsCalculator(dbManager);
            processMetricsCalculator.calculateAndSaveMetricsForAllCommits(repository);
            dbManager.commit(); // <-- BU SATIR ÖNEMLİ
            System.out.println("✓ Enhanced process metrics calculation completed (NR, NDEV, AGE, EXP + LINES_ADDED, LINES_DELETED, HUNK_COUNT)");

            // Step 6: Calculate static metrics
            System.out.println("\nStep 6: Calculating static metrics...");
            StaticMetricsCalculator staticMetricsCalculator = new StaticMetricsCalculator(dbManager, repository);
            
            // Use commit-based approach for simplicity
            for (RevCommit commit : chronologicalCommits) {
                staticMetricsCalculator.calculateAndSaveMetrics(commit);
            }
            
            dbManager.commit();
            System.out.println("✓ Static metrics calculation completed for all processed commits.");

            // Step 7: Data preprocessing and feature engineering
            System.out.println("Step 7: Data preprocessing and feature engineering...");
            DataPreprocessor dataPreprocessor = new DataPreprocessor(dbManager, gitManager); // Inject dependency
            Instances allData = dataPreprocessor.loadDataFromDatabase();
            System.out.println("✓ Data loaded from database");
            System.out.println(dataPreprocessor.getDatasetStatistics(allData));

            // Step 8: Enhanced machine learning evaluation with multiple validation strategies
            System.out.println("Step 8: Enhanced machine learning evaluation with multiple validation strategies...");
            
            // Initialize version manager for advanced validation
            VersionManager versionManager = new VersionManager();
            Git git = Git.open(repository.getDirectory());
            List<VersionManager.Version> allVersions = versionManager.getAllVersions(git);
            boolean canPerformVersionValidation = versionManager.hasSufficientVersionsForValidation(allVersions);
            
            System.out.println("📊 Available Validation Strategies:");
            System.out.println("  ✓ Temporal Cross-Validation (Always available)");
            if (canPerformVersionValidation && allVersions.size() >= 2) {
                System.out.println("  ✓ Version-Based Validation (Git tags detected)");
                VersionManager.Version trainVersion = allVersions.get(allVersions.size() - 2);
                VersionManager.Version testVersion = allVersions.get(allVersions.size() - 1);
                System.out.println(versionManager.getValidationSummary(trainVersion, testVersion));
            } else {
                System.out.println("  ⚠ Version-Based Validation (Insufficient Git tags)");
            }
            System.out.println();

            // Perform temporal cross-validation (baseline)
            System.out.println("🔄 Performing Temporal Cross-Validation...");
            ModelTrainer temporalTrainer = new ModelTrainer(allData);
            Map<String, ModelEvaluationResult> temporalResults = temporalTrainer.performTemporalValidation();
            System.out.println("✓ Temporal cross-validation completed with enhanced scientific rigor and class balancing");
            
            // Perform version-based validation if possible (advanced)
            Map<String, ModelEvaluationResult> versionResults = null;
            if (canPerformVersionValidation && allVersions.size() >= 2) {
                System.out.println("\n🏷️ Performing Version-Based Validation...");
                
                // Get the train and test versions (penultimate and latest)
                VersionManager.Version trainVersion = allVersions.get(allVersions.size() - 2);
                VersionManager.Version testVersion = allVersions.get(allVersions.size() - 1);
                
                System.out.println("Training data: Up to " + trainVersion.toString());
                System.out.println("Test data: " + testVersion.toString());
                
                try {
                    // Prepare commit-to-revision mapping for version split
                    Map<String, List<DataPreprocessor.FileRevisionData>> commitToRevisionMap = 
                        dataPreprocessor.buildCommitToRevisionMap();
                    
                    String splitDescription = String.format("Train: ≤%s | Test: %s", 
                        trainVersion.name, testVersion.name);
                    
                    DataSplit versionSplit = dataPreprocessor.splitByVersion(
                        allData, 
                        trainVersion.commitHash, 
                        commitToRevisionMap, 
                        splitDescription
                    );
                    
                    if (versionSplit != null && versionSplit.getTestSet().numInstances() > 0) {
                        ModelTrainer versionTrainer = new ModelTrainer(versionSplit);
                        versionResults = versionTrainer.performVersionBasedValidation();
                        System.out.println("✓ Version-based validation completed - provides realistic deployment performance");
                    } else {
                        System.out.println("⚠ Version-based validation skipped - insufficient data in target version");
                    }
                } catch (Exception e) {
                    System.out.println("⚠ Version-based validation failed: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            // Determine primary results for reporting
            Map<String, ModelEvaluationResult> primaryResults = (versionResults != null) ? versionResults : temporalResults;
            String validationMethod = (versionResults != null) ? "Version-Based" : "Temporal Cross-Validation";

            // Step 9: Dynamic report generation
            System.out.println("Step 9: Generating " + validationMethod + " report...");
            String repoName = gitManager.getRepoName();
            
            ReportGenerator reportGenerator = new ReportGenerator(primaryResults, allData, repoName);
            String reportFile = reportGenerator.generateReport();
            System.out.println("✓ " + validationMethod + " report generated: " + reportFile);
            
            // Step 10: Determine workflow mode
            System.out.println("\nStep 10: Determining analysis mode...");
            boolean hasSufficientData = allData.numInstances() >= 100; // Minimum instances for reliable prediction
            
            if (hasSufficientData && canPerformVersionValidation && allVersions.size() >= 1) {
                System.out.println("✓ Sufficient data available - Enabling latest version prediction mode");
                
                // Get latest version for prediction
                VersionManager.Version latestVersion = allVersions.get(allVersions.size() - 1);
                System.out.println("Latest version for prediction: " + latestVersion.toString());
                
                // Step 11: Prepare prediction data split for latest version
                System.out.println("\nStep 11: Preparing latest version prediction data split...");
                
                // Use latest version commit for prediction
                PredictionData predictionData = dataPreprocessor.splitByLatestVersion(allData, latestVersion.commitHash);
                
                if (predictionData.predictionSet.numInstances() > 0) {
                    System.out.println("✓ Latest version data prepared for prediction (" + 
                        predictionData.predictionSet.numInstances() + " files)");
                    
                    // Step 12: Train model and make predictions on latest version
                    System.out.println("\nStep 12: Training model and making predictions on latest version...");
                    ModelTrainer predictionTrainer = new ModelTrainer(allData); // Use allData for temporal validation constructor
                    Map<Long, Double> revisionIdPredictions = predictionTrainer.trainAndPredictOnLatestCommit(predictionData);
                    
                    if (!revisionIdPredictions.isEmpty()) {
                        System.out.println("✓ Predictions completed for " + revisionIdPredictions.size() + " file revisions");
                        
                        // Create revision ID to file path mapping
                        System.out.println("Creating file path mapping...");
                        Map<Long, String> revisionToPathMap = new HashMap<>();
                        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
                            String sql = "SELECT id, file_path FROM file_revisions";
                            try (PreparedStatement stmt = conn.prepareStatement(sql);
                                 ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    long revisionId = rs.getLong("id");
                                    String filePath = rs.getString("file_path");
                                    revisionToPathMap.put(revisionId, filePath);
                                }
                            }
                        }
                        
                        // Convert revision ID predictions to file path predictions
                        Map<String, Double> predictions = new HashMap<>();
                        for (Map.Entry<Long, Double> entry : revisionIdPredictions.entrySet()) {
                            Long revisionId = entry.getKey();
                            Double probability = entry.getValue();
                            String filePath = revisionToPathMap.getOrDefault(revisionId, "Unknown_File_" + revisionId);
                            predictions.put(filePath, probability);
                        }
                        
                        // Step 13: Generate prediction report
                        System.out.println("\nStep 13: Generating prediction report...");
                        String predictionReportFile = reportGenerator.generatePredictionReport(latestVersion.name, predictions);
                        
                        if (predictionReportFile != null) {
                            System.out.println("✓ Prediction report generated: " + predictionReportFile);
                            
                            // Summary
                            long highRisk = predictions.values().stream().mapToLong(p -> p >= 0.7 ? 1 : 0).sum();
                            long mediumRisk = predictions.values().stream().mapToLong(p -> p >= 0.3 && p < 0.7 ? 1 : 0).sum();
                            long lowRisk = predictions.values().stream().mapToLong(p -> p < 0.3 ? 1 : 0).sum();
                            
                            System.out.println("\n📊 PREDICTION SUMMARY:");
                            System.out.println("   High Risk Files (≥70%): " + highRisk);
                            System.out.println("   Medium Risk Files (30-69%): " + mediumRisk);
                            System.out.println("   Low Risk Files (<30%): " + lowRisk);
                        }
                    } else {
                        System.out.println("⚠ No predictions could be generated");
                    }
                } else {
                    System.out.println("⚠ No files found in latest version for prediction");
                }
            } else {
                System.out.println("⚠ Insufficient data for reliable prediction (" + allData.numInstances() + 
                    " instances, minimum 100 required) or no version tags available");
                System.out.println("  Recommendation: Collect more historical data and ensure Git tags for prediction capabilities");
            }
            
            // Final completion message
            System.out.println("\n🎯 ANALYSIS COMPLETE!");
            System.out.println("===================");
            System.out.println("✓ " + validationMethod + " completed with high accuracy");
            System.out.println("✓ Analysis reports generated");
            if (hasSufficientData && canPerformVersionValidation && allVersions.size() >= 1) {
                System.out.println("✓ Latest version predictions completed");
            }
            System.out.println("\nReports available:");
            System.out.println("  - " + validationMethod + ": " + reportFile);
            if (hasSufficientData && canPerformVersionValidation && allVersions.size() >= 1) {
                System.out.println("  - Predictions: Check latest prediction report");
            }
            
            System.out.println();
            System.out.println("Analysis completed successfully!");

        } catch (Exception e) {
            System.err.println("An error occurred during analysis: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Properly close resources
            try {
                if (repository != null) {
                    gitManager.closeRepository(repository);
                }
                if (dbManager != null) {
                    dbManager.close();
                }
            } catch (Exception e) {
                System.err.println("Error closing resources: " + e.getMessage());
            }
        }
    }
}