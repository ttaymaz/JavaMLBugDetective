package org.tymz.ml;

import weka.classifiers.Classifier;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.SMO;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.J48;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.Instance;
import weka.core.Attribute;
import weka.classifiers.CostMatrix;
import weka.filters.Filter;
import weka.filters.supervised.instance.ClassBalancer;
import weka.filters.MultiFilter;
import weka.filters.unsupervised.attribute.Normalize;

import org.tymz.config.Config;
import org.tymz.feature.DataPreprocessor.PredictionData;
import org.tymz.feature.DataPreprocessor.DataSplit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;


/**
 * ModelTrainer handles the training and evaluation of multiple machine learning models
 * for bug prediction using WEKA classifiers.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * This class:
 * 1. Trains multiple classification algorithms on the training set
 * 2. Evaluates each model on the test set
 * 3. Reports performance metrics including accuracy, precision, recall, F1-score
 * 4. Displays confusion matrices for detailed analysis
 *
 * The class supports standard algorithms including Naive Bayes, Decision Trees (J48),
 * Random Forest, and Support Vector Machines (SMO) with cost-sensitive classification.
 */
public class ModelTrainer {
    
    private final Instances allData;
    private DataSplit dataSplit; // For version-based validation
    
    /**
     * Constructor initializes the ModelTrainer with complete dataset for cross-validation
     * 
     * @param allData WEKA Instances object containing all data for cross-validation
     */
    public ModelTrainer(Instances allData) {
        this.allData = allData;
        this.dataSplit = null;
    }
    
    /**
     * Constructor initializes the ModelTrainer with DataSplit for version-based validation
     * 
     * @param dataSplit DataSplit object containing training and test sets based on versions
     */
    public ModelTrainer(DataSplit dataSplit) {
        this.dataSplit = dataSplit;
        this.allData = null;
    }
    
    /**
     * Performs time-aware validation on all models for enhanced scientific validity.
     * Uses temporal split (80% training, 20% test) instead of cross-validation to avoid data leakage.
     * 
     * @return Map containing model names and their corresponding ModelEvaluationResult objects
     * @throws Exception if model training or evaluation fails
     */
    public Map<String, ModelEvaluationResult> performTemporalValidation() throws Exception {
        // Initialize results map to store evaluation results
        Map<String, ModelEvaluationResult> resultsMap = new HashMap<>();
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("ENHANCED MODEL EVALUATION WITH TIME-AWARE VALIDATION");
        System.out.println("=".repeat(70));
        
        System.out.printf("Total Dataset: %d instances%n", allData.numInstances());
        System.out.printf("Features: %d attributes%n", allData.numAttributes() - 1);
        System.out.println("Using temporal split (80% training, 20% test) to avoid data leakage");
        System.out.println();
        
        // Create temporal split (preserving chronological order)
        int trainSize = (int) Math.round(allData.numInstances() * 0.8);
        int testSize = allData.numInstances() - trainSize;
        
        Instances trainingSet = new Instances(allData, 0, trainSize);
        Instances testSet = new Instances(allData, trainSize, testSize);
        
        // Check class distribution in both sets
        int[] trainClassCounts = new int[allData.numClasses()];
        int[] testClassCounts = new int[allData.numClasses()];
        
        for (int i = 0; i < trainingSet.numInstances(); i++) {
            trainClassCounts[(int) trainingSet.instance(i).classValue()]++;
        }
        
        for (int i = 0; i < testSet.numInstances(); i++) {
            testClassCounts[(int) testSet.instance(i).classValue()]++;
        }
        
        System.out.printf("Training set: %d instances (%.1f%%)%n", trainSize, 80.0);
        System.out.printf("  - Clean: %d (%.1f%%), Buggy: %d (%.1f%%)%n", 
            trainClassCounts[0], (trainClassCounts[0] * 100.0) / trainSize,
            trainClassCounts[1], (trainClassCounts[1] * 100.0) / trainSize);
            
        System.out.printf("Test set: %d instances (%.1f%%)%n", testSize, 20.0);
        System.out.printf("  - Clean: %d (%.1f%%), Buggy: %d (%.1f%%)%n", 
            testClassCounts[0], (testClassCounts[0] * 100.0) / testSize,
            testClassCounts[1], (testClassCounts[1] * 100.0) / testSize);
            
        // Check if test set has sufficient class balance (at least 5% of minority class)
        boolean useTemporalSplit = testClassCounts[0] > 0 && testClassCounts[1] > 0 && 
                                  Math.min(testClassCounts[0], testClassCounts[1]) >= testSize * 0.05;
        
        if (!useTemporalSplit) {
            System.out.println("⚠ Test set has insufficient class balance for temporal split");
            System.out.println("  Falling back to 10-fold cross-validation for robust evaluation");
            return performCrossValidation();
        }
        
        System.out.println("✓ Test set has sufficient class balance, proceeding with temporal split");
        System.out.println();
        
        // Create list of base classifiers to evaluate
        List<Classifier> baseClassifiers = createClassifiers();
        
        // Evaluate each classifier using temporal validation with class balancing
        for (Classifier classifier : baseClassifiers) {
            String baseModelName = classifier.getClass().getSimpleName();
            
            System.out.println("-".repeat(60));
            System.out.println("EVALUATING MODEL: " + baseModelName + " with Time-Aware Validation");
            System.out.println("-".repeat(60));
            
            try {
                // --- START: COST-SENSITIVE CLASSIFICATION SETUP ---
                CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
                // Set the base classifier to be wrapped
                costSensitive.setClassifier(classifier); 
                // Tell the classifier to minimize the expected cost, not just error rate
                costSensitive.setMinimizeExpectedCost(true); 

                // Create a cost matrix. We will penalize False Negatives 10 times more than False Positives.
                // Matrix format:
                // Row = Actual Class, Column = Predicted Class
                // M[0][0] = True Negative Cost (clean as clean)
                // M[0][1] = False Positive Cost (clean as buggy)
                // M[1][0] = False Negative Cost (buggy as clean) -> THIS IS THE CRITICAL ONE
                // M[1][1] = True Positive Cost (buggy as buggy)
                CostMatrix costMatrix = new CostMatrix(2);
                costMatrix.setCell(0, 0, 0.0);  // True Negative Cost (clean as clean)
                costMatrix.setCell(0, 1, Config.getMLCostFP()); // False Positive Cost (clean as buggy) - from config
                costMatrix.setCell(1, 0, Config.getMLCostFN()); // False Negative Cost (buggy as clean) - from config
                costMatrix.setCell(1, 1, 0.0);  // True Positive Cost (buggy as buggy)
                costSensitive.setCostMatrix(costMatrix);

                // The 'costSensitive' object now acts as our base classifier for the next steps
                Classifier classifierForFiltering = costSensitive;
                String modelDescription = baseModelName + " (Cost-Sensitive)";
                // --- END: COST-SENSITIVE CLASSIFICATION SETUP ---
                
                Classifier finalClassifier = classifierForFiltering;
                
                // Apply class balancing if enabled in config
                if (Config.isMLBalanceClasses()) {
                    // Create FilteredClassifier with normalization and SMOTE/ClassBalancer
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    
                    // Create normalization filter
                    Normalize normalizeFilter = new Normalize();
                    
                    // Try to use SMOTE first (requires package to be loaded)
                    Object balancingFilter = null;
                    String filterName = "SMOTE";
                    
                    try {
                        // Dynamically load SMOTE filter if available
                        Class<?> smoteClass = Class.forName("weka.filters.supervised.instance.SMOTE");
                        balancingFilter = smoteClass.getDeclaredConstructor().newInstance();
                        System.out.println("Using SMOTE filter for advanced class balancing");
                    } catch (Exception smoteEx) {
                        // Fallback to ClassBalancer if SMOTE is not available
                        balancingFilter = new ClassBalancer();
                        filterName = "ClassBalancer";
                        System.out.println("SMOTE not available, using ClassBalancer as fallback");
                    }
                    
                    // Create MultiFilter to chain normalization and balancing
                    MultiFilter multiFilter = new MultiFilter();
                    Filter[] filters = {normalizeFilter, (Filter) balancingFilter};
                    multiFilter.setFilters(filters);
                    
                    // Configure the filtered classifier
                    filteredClassifier.setFilter(multiFilter);
                    filteredClassifier.setClassifier(classifierForFiltering); // Use the cost-sensitive one
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized + Balanced with " + filterName + ")";
                } else {
                    // Apply only normalization if class balancing is disabled
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    Normalize normalizeFilter = new Normalize();
                    filteredClassifier.setFilter(normalizeFilter);
                    filteredClassifier.setClassifier(classifierForFiltering); // Use the cost-sensitive one
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized)";
                    System.out.println("Class balancing disabled in config, applying normalization only");
                }
                
                // Perform time-aware validation
                System.out.print("Training " + modelDescription + " on temporal training set... ");
                long startTime = System.currentTimeMillis();
                
                // Train the classifier on the training set
                finalClassifier.buildClassifier(trainingSet);
                
                // Create evaluation object with training set as baseline
                Evaluation eval = new Evaluation(trainingSet);
                
                // --- START: MODIFIED EVALUATION AND REPORTING BLOCK ---
                // Standard Weka evaluation
                eval.evaluateModel(finalClassifier, testSet);
                long evalTime = System.currentTimeMillis() - startTime;
                System.out.println("✓ Completed in " + evalTime + "ms");

                // Calculate effort-aware metrics
                Map<String, Double> effortMetrics;
                try {
                    effortMetrics = EffortAwareMetrics.calculate(finalClassifier, testSet);
                    System.out.printf("  - Recall@20%%Effort: %.4f%n", effortMetrics.getOrDefault("Recall@20%Effort", 0.0));
                    System.out.printf("  - Popt (Optimal): %.4f%n", effortMetrics.getOrDefault("Popt", 0.0));
                } catch (Exception e) {
                    System.err.println("  Could not calculate effort-aware metrics: " + e.getMessage());
                    effortMetrics = new HashMap<>(); // Ensure map is not null
                }

                // Bundle all results into our DTO
                ModelEvaluationResult combinedResult = new ModelEvaluationResult(eval, effortMetrics);

                // Store the combined result object for report generation
                resultsMap.put(baseModelName, combinedResult);
                // --- END: MODIFIED EVALUATION AND REPORTING BLOCK ---
                
                // Print detailed validation results
                printCrossValidationResults(modelDescription, eval);
                
            } catch (Exception e) {
                System.out.println("✗ FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            
            System.out.println();
        }
        
        System.out.println("=".repeat(70));
        System.out.println("TIME-AWARE VALIDATION COMPLETED");
        System.out.println("=".repeat(70));
        
        // Return the results map for report generation
        return resultsMap;
    }
    
    /**
     * Creates and returns a list of WEKA classifiers to train and evaluate
     * 
     * @return List of configured classifiers
     */
    private List<Classifier> createClassifiers() {
        List<Classifier> classifiers = new ArrayList<>();
        
        String algorithm = Config.getMLAlgorithm();
        
        switch (algorithm.toLowerCase()) {
            case "naive_bayes":
            case "naivebayes":
                classifiers.add(new NaiveBayes());
                break;
            case "j48":
                classifiers.add(new J48());
                break;
            case "smo":
                classifiers.add(new SMO());
                break;
            case "random_forest":
            case "randomforest":
                RandomForest rf = new RandomForest();
                rf.setNumIterations(100); // Set number of trees
                classifiers.add(rf);
                break;
            case "all":
            default:
                // Run all algorithms for comparison
                classifiers.add(new NaiveBayes());
                classifiers.add(new J48());
                RandomForest rf2 = new RandomForest();
                rf2.setNumIterations(100);
                classifiers.add(rf2);
                classifiers.add(new SMO());
        }
        
        return classifiers;
    }
    
    /**
     * Prints comprehensive cross-validation results for a specific model.
     * This method provides detailed performance metrics and statistical analysis.
     * 
     * @param modelName The name of the model being evaluated
     * @param eval The Evaluation object containing cross-validation results
     * @throws Exception if printing results fails
     */
    private void printCrossValidationResults(String modelName, Evaluation eval) throws Exception {
        System.out.println("\n📊 " + modelName + " - Cross-Validation Results:");
        System.out.println("▬".repeat(50));
        
        // Overall Accuracy Metrics
        System.out.printf("✓ Accuracy: %.4f (%.2f%%)%n", 
            eval.pctCorrect() / 100, eval.pctCorrect());
        System.out.printf("✗ Error Rate: %.4f (%.2f%%)%n", 
            eval.pctIncorrect() / 100, eval.pctIncorrect());
        
        System.out.println();
        
        // Binary Classification Metrics
        try {
            System.out.println("📈 Binary Classification Metrics:");
            System.out.printf("  • Precision (Buggy): %.4f%n", eval.precision(getBuggyClassIndex()));
            System.out.printf("  • Recall (Buggy): %.4f%n", eval.recall(getBuggyClassIndex()));
            System.out.printf("  • F1-Score (Buggy): %.4f%n", eval.fMeasure(getBuggyClassIndex()));
            System.out.printf("  • AUC-ROC: %.4f%n", eval.areaUnderROC(getBuggyClassIndex()));
            System.out.printf("  • AUC-PRC: %.4f%n", eval.areaUnderPRC(getBuggyClassIndex()));
        } catch (Exception e) {
            System.out.println("  ⚠️ Binary metrics calculation failed: " + e.getMessage());
        }
        
        System.out.println();
        
        // Confusion Matrix
        System.out.println("📋 Confusion Matrix:");
        double[][] confusionMatrix = eval.confusionMatrix();
        System.out.println("           Predicted");
        System.out.println("         Clean  Buggy");
        System.out.printf("Clean  | %6.0f %6.0f |%n", confusionMatrix[0][0], confusionMatrix[0][1]);
        System.out.printf("Buggy  | %6.0f %6.0f |%n", confusionMatrix[1][0], confusionMatrix[1][1]);
        
        System.out.println();
        
        // Statistical Significance
        System.out.println("📊 Statistical Analysis:");
        System.out.printf("  • Total Instances: %.0f%n", eval.numInstances());
        System.out.printf("  • Correctly Classified: %.0f%n", eval.correct());
        System.out.printf("  • Incorrectly Classified: %.0f%n", eval.incorrect());
        System.out.printf("  • Kappa Statistic: %.4f%n", eval.kappa());
        System.out.printf("  • Mean Absolute Error: %.4f%n", eval.meanAbsoluteError());
        System.out.printf("  • Root Mean Squared Error: %.4f%n", eval.rootMeanSquaredError());
        
        System.out.println("▬".repeat(50));
    }
    
    /**
     * Gets the index of the 'buggy' class in the dataset.
     * This is used for binary classification metrics calculation.
     * 
     * @return The index of the buggy class, or 1 as default
     */
    private int getBuggyClassIndex() {
        Instances data = allData != null ? allData : dataSplit.getTrainingSet();
        for (int i = 0; i < data.numClasses(); i++) {
            if ("buggy".equals(data.classAttribute().value(i))) {
                return i;
            }
        }
        // Default assumption: buggy is class index 1
        return 1;
    }
    
    /**
     * Train on historical data and predict bug probabilities for latest commit files.
     * Uses RandomForest with SMOTE for class balancing.
     * 
     * @param predictionData PredictionData containing training set, prediction set, and revision IDs
     * @return Map of file revision IDs to predicted bug probabilities
     * @throws Exception if training or prediction fails
     */
    public Map<Long, Double> trainAndPredictOnLatestCommit(PredictionData predictionData) throws Exception {
        Instances trainingSet = predictionData.trainingSet;
        Instances predictionSet = predictionData.predictionSet;
        List<Long> predictionRevisionIds = predictionData.predictionRevisionIds;
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("BUG PREDICTION FOR LATEST COMMIT");
        System.out.println("=".repeat(70));
        System.out.println("Training set: " + trainingSet.numInstances() + " instances");
        System.out.println("Prediction set: " + predictionSet.numInstances() + " instances");
        System.out.println("Revision IDs to track: " + predictionRevisionIds.size());
        
        // Apply SMOTE to balance the training set
        Instances balancedTrainingSet = applySmoteBalancing(trainingSet);
        System.out.println("Balanced training set: " + balancedTrainingSet.numInstances() + " instances");
        
        // Train RandomForest on balanced training set
        RandomForest classifier = new RandomForest();
        classifier.setNumIterations(100);
        classifier.buildClassifier(balancedTrainingSet);
        
        System.out.println("✓ RandomForest model trained successfully");
        
        // Make predictions for each instance in the prediction set
        Map<Long, Double> predictions = new HashMap<>();
        int buggyClassIndex = getBuggyClassIndex();
        
        for (int i = 0; i < predictionSet.numInstances(); i++) {
            Instance instance = predictionSet.instance(i);
            
            // Get probability distribution
            double[] distribution = classifier.distributionForInstance(instance);
            double bugProbability = distribution[buggyClassIndex];
            
            // Get corresponding revision ID from the tracked list
            long revisionId = predictionRevisionIds.get(i);
            
            predictions.put(revisionId, bugProbability);
        }
        
        System.out.println("✓ Predictions completed for " + predictions.size() + " file revisions");
        
        return predictions;
    }
    
    /**
     * Apply SMOTE balancing to the training set.
     * Falls back to ClassBalancer if SMOTE is not available.
     * 
     * @param trainingSet The original training set
     * @return Balanced training set
     * @throws Exception if balancing fails
     */
    private Instances applySmoteBalancing(Instances trainingSet) throws Exception {
        try {
            // Try to use SMOTE first
            Class<?> smoteClass = Class.forName("weka.filters.supervised.instance.SMOTE");
            Filter smoteFilter = (Filter) smoteClass.getDeclaredConstructor().newInstance();
            smoteFilter.setInputFormat(trainingSet);
            Instances balancedData = Filter.useFilter(trainingSet, smoteFilter);
            
            System.out.println("✓ Applied SMOTE balancing");
            return balancedData;
            
        } catch (Exception smoteEx) {
            // Fallback to ClassBalancer
            ClassBalancer balancer = new ClassBalancer();
            balancer.setInputFormat(trainingSet);
            Instances balancedData = Filter.useFilter(trainingSet, balancer);
            
            System.out.println("✓ Applied ClassBalancer (SMOTE not available)");
            return balancedData;
        }
    }
    
    /**
     * Performs version-based validation on all models for enhanced scientific validity.
     * 
     * This method implements a version-based validation approach where models are trained
     * on historical software versions and tested on future releases. This methodology
     * provides realistic performance evaluation that mirrors real-world deployment scenarios.
     * 
     * Unlike traditional cross-validation that randomly shuffles data, version-based validation
     * respects the temporal evolution of software projects and provides actionable insights
     * for practical bug prediction deployment.
     * 
     * @return Map containing model names and their corresponding ModelEvaluationResult objects
     * @throws Exception if model training or evaluation fails
     */
    public Map<String, ModelEvaluationResult> performVersionBasedValidation() throws Exception {
        if (dataSplit == null) {
            throw new IllegalStateException("DataSplit is required for version-based validation");
        }
        
        // Initialize results map to store evaluation results
        Map<String, ModelEvaluationResult> resultsMap = new HashMap<>();
        
        Instances trainingSet = dataSplit.getTrainingSet();
        Instances testSet = dataSplit.getTestSet();
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("VERSION-BASED VALIDATION FOR BUG PREDICTION");
        System.out.println("=".repeat(80));
        
        System.out.println("Validation Strategy: " + dataSplit.getSplitDescription());
        System.out.printf("Training Set (Historical Versions): %d instances%n", trainingSet.numInstances());
        System.out.printf("Test Set (Target Version): %d instances%n", testSet.numInstances());
        System.out.printf("Features: %d attributes%n", trainingSet.numAttributes() - 1);
        System.out.println("Methodology: Train on version history → Predict future version bugs");
        System.out.println();
        
        if (testSet.numInstances() == 0) {
            System.out.println("⚠️ WARNING: No test instances available. Cannot perform validation.");
            return resultsMap;
        }
        
        // Create list of base classifiers to evaluate
        List<Classifier> baseClassifiers = createClassifiers();
        
        // Evaluate each classifier using version-based validation with class balancing
        for (Classifier classifier : baseClassifiers) {
            String baseModelName = classifier.getClass().getSimpleName();
            
            System.out.println("-".repeat(70));
            System.out.println("EVALUATING MODEL: " + baseModelName + " with Version-Based Validation");
            System.out.println("-".repeat(70));
            
            try {
                // --- START: COST-SENSITIVE CLASSIFICATION SETUP ---
                CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
                // Set the base classifier to be wrapped
                costSensitive.setClassifier(classifier); 
                // Tell the classifier to minimize the expected cost, not just error rate
                costSensitive.setMinimizeExpectedCost(true); 

                // Create a cost matrix. We will penalize False Negatives 10 times more than False Positives.
                // Matrix format:
                // Row = Actual Class, Column = Predicted Class
                // M[0][0] = True Negative Cost (clean as clean)
                // M[0][1] = False Positive Cost (clean as buggy)
                // M[1][0] = False Negative Cost (buggy as clean) -> THIS IS THE CRITICAL ONE
                // M[1][1] = True Positive Cost (buggy as buggy)
                CostMatrix costMatrix = new CostMatrix(2);
                costMatrix.setCell(0, 0, 0.0);  // True Negative Cost (clean as clean)
                costMatrix.setCell(0, 1, Config.getMLCostFP()); // False Positive Cost (clean as buggy) - from config
                costMatrix.setCell(1, 0, Config.getMLCostFN()); // False Negative Cost (buggy as clean) - from config
                costMatrix.setCell(1, 1, 0.0);  // True Positive Cost (buggy as buggy)
                costSensitive.setCostMatrix(costMatrix);

                // The 'costSensitive' object now acts as our base classifier for the next steps
                Classifier classifierForFiltering = costSensitive;
                String modelDescription = baseModelName + " (Cost-Sensitive)";
                // --- END: COST-SENSITIVE CLASSIFICATION SETUP ---
                
                Classifier finalClassifier = classifierForFiltering;
                
                // Apply class balancing if enabled in config
                if (Config.isMLBalanceClasses()) {
                    // Create FilteredClassifier with normalization and SMOTE/ClassBalancer
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    
                    // Create normalization filter
                    Normalize normalizeFilter = new Normalize();
                    
                    // Try to use SMOTE first (requires package to be loaded)
                    Object balancingFilter = null;
                    String filterName = "SMOTE";
                    
                    try {
                        // Dynamically load SMOTE filter if available
                        Class<?> smoteClass = Class.forName("weka.filters.supervised.instance.SMOTE");
                        balancingFilter = smoteClass.getDeclaredConstructor().newInstance();
                        System.out.println("Using SMOTE filter for advanced class balancing");
                    } catch (Exception smoteEx) {
                        // Fallback to ClassBalancer if SMOTE is not available
                        balancingFilter = new ClassBalancer();
                        filterName = "ClassBalancer";
                        System.out.println("SMOTE not available, using ClassBalancer as fallback");
                    }
                    
                    // Create MultiFilter to chain normalization and balancing
                    MultiFilter multiFilter = new MultiFilter();
                    Filter[] filters = {normalizeFilter, (Filter) balancingFilter};
                    multiFilter.setFilters(filters);
                    
                    // Configure the filtered classifier
                    filteredClassifier.setFilter(multiFilter);
                    filteredClassifier.setClassifier(classifierForFiltering); // Use the cost-sensitive one
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized + Balanced with " + filterName + ")";
                } else {
                    // Apply only normalization if class balancing is disabled
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    Normalize normalizeFilter = new Normalize();
                    filteredClassifier.setFilter(normalizeFilter);
                    filteredClassifier.setClassifier(classifierForFiltering); // Use the cost-sensitive one
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized)";
                    System.out.println("Class balancing disabled in config, applying normalization only");
                }
                
                // Perform version-based validation
                System.out.print("Training " + modelDescription + " on historical versions... ");
                long startTime = System.currentTimeMillis();
                
                // Train the classifier on the historical training set
                finalClassifier.buildClassifier(trainingSet);
                
                // Create evaluation object with training set as baseline
                Evaluation eval = new Evaluation(trainingSet);
                
                // --- START: MODIFIED EVALUATION AND REPORTING BLOCK ---
                // Standard Weka evaluation
                eval.evaluateModel(finalClassifier, testSet);
                long evalTime = System.currentTimeMillis() - startTime;
                System.out.println("✓ Completed in " + evalTime + "ms");

                // Calculate effort-aware metrics
                Map<String, Double> effortMetrics;
                try {
                    effortMetrics = EffortAwareMetrics.calculate(finalClassifier, testSet);
                    System.out.printf("  - Recall@20%%Effort: %.4f%n", effortMetrics.getOrDefault("Recall@20%Effort", 0.0));
                    System.out.printf("  - Popt (Optimal): %.4f%n", effortMetrics.getOrDefault("Popt", 0.0));
                } catch (Exception e) {
                    System.err.println("  Could not calculate effort-aware metrics: " + e.getMessage());
                    effortMetrics = new HashMap<>(); // Ensure map is not null
                }

                // Bundle all results into our DTO
                ModelEvaluationResult combinedResult = new ModelEvaluationResult(eval, effortMetrics);

                // Store the combined result object for report generation
                resultsMap.put(baseModelName, combinedResult);
                // --- END: MODIFIED EVALUATION AND REPORTING BLOCK ---
                
                // Print detailed validation results
                printVersionBasedValidationResults(modelDescription, eval);
                
            } catch (Exception e) {
                System.out.println("✗ FAILED: " + e.getMessage());
                e.printStackTrace();
            }
            
            System.out.println();
        }
        
        System.out.println("=".repeat(80));
        System.out.println("VERSION-BASED VALIDATION COMPLETED");
        System.out.println("=".repeat(80));
        
        // Return the results map for report generation
        return resultsMap;
    }
    
    /**
     * Prints comprehensive version-based validation results for a specific model.
     * This method provides detailed performance metrics and statistical analysis.
     * 
     * @param modelName The name of the model being evaluated
     * @param eval The Evaluation object containing validation results
     * @throws Exception if printing results fails
     */
    private void printVersionBasedValidationResults(String modelName, Evaluation eval) throws Exception {
        System.out.println("\n📊 " + modelName + " - Version-Based Validation Results:");
        System.out.println("▬".repeat(60));
        
        // Overall Accuracy Metrics
        System.out.printf("✓ Accuracy: %.4f (%.2f%%)%n", 
            eval.pctCorrect() / 100, eval.pctCorrect());
        System.out.printf("✗ Error Rate: %.4f (%.2f%%)%n", 
            eval.pctIncorrect() / 100, eval.pctIncorrect());
        
        System.out.println();
        
        // Binary Classification Metrics
        try {
            System.out.println("📈 Binary Classification Metrics:");
            System.out.printf("  • Precision (Buggy): %.4f%n", eval.precision(getBuggyClassIndex()));
            System.out.printf("  • Recall (Buggy): %.4f%n", eval.recall(getBuggyClassIndex()));
            System.out.printf("  • F1-Score (Buggy): %.4f%n", eval.fMeasure(getBuggyClassIndex()));
            System.out.printf("  • AUC-ROC: %.4f%n", eval.areaUnderROC(getBuggyClassIndex()));
            System.out.printf("  • AUC-PRC: %.4f%n", eval.areaUnderPRC(getBuggyClassIndex()));
        } catch (Exception e) {
            System.out.println("  ⚠️ Binary metrics calculation failed: " + e.getMessage());
        }
        
        System.out.println();
        
        // Confusion Matrix
        System.out.println("📋 Confusion Matrix:");
        double[][] confusionMatrix = eval.confusionMatrix();
        System.out.println("           Predicted");
        System.out.println("         Clean  Buggy");
        System.out.printf("Clean  | %6.0f %6.0f |%n", confusionMatrix[0][0], confusionMatrix[0][1]);
        System.out.printf("Buggy  | %6.0f %6.0f |%n", confusionMatrix[1][0], confusionMatrix[1][1]);
        
        System.out.println();
        
        // Statistical Significance
        System.out.println("📊 Statistical Analysis:");
        System.out.printf("  • Total Test Instances: %.0f%n", eval.numInstances());
        System.out.printf("  • Correctly Classified: %.0f%n", eval.correct());
        System.out.printf("  • Incorrectly Classified: %.0f%n", eval.incorrect());
        System.out.printf("  • Kappa Statistic: %.4f%n", eval.kappa());
        System.out.printf("  • Mean Absolute Error: %.4f%n", eval.meanAbsoluteError());
        System.out.printf("  • Root Mean Squared Error: %.4f%n", eval.rootMeanSquaredError());
        
        System.out.println("▬".repeat(60));
    }

    /**
     * Performs 10-fold cross-validation with cost-sensitive classification as fallback.
     * 
     * @return Map containing model names and their corresponding ModelEvaluationResult objects
     * @throws Exception if model training or evaluation fails
     */
    private Map<String, ModelEvaluationResult> performCrossValidation() throws Exception {
        Map<String, ModelEvaluationResult> resultsMap = new HashMap<>();
        
        System.out.println("🔄 Performing 10-fold Cross-Validation with Cost-Sensitive Classification...");
        System.out.println();
        
        // Create list of base classifiers to evaluate
        List<Classifier> baseClassifiers = createClassifiers();
        
        // Evaluate each classifier using 10-fold cross-validation
        for (Classifier classifier : baseClassifiers) {
            String baseModelName = classifier.getClass().getSimpleName();
            
            System.out.println("-".repeat(60));
            System.out.println("EVALUATING MODEL: " + baseModelName + " with 10-fold Cross-Validation");
            System.out.println("-".repeat(60));
            
            try {
                // --- START: COST-SENSITIVE CLASSIFICATION SETUP ---
                CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
                costSensitive.setClassifier(classifier); 
                costSensitive.setMinimizeExpectedCost(true); 

                CostMatrix costMatrix = new CostMatrix(2);
                costMatrix.setCell(0, 0, 0.0);  // True Negative Cost (clean as clean)
                costMatrix.setCell(0, 1, Config.getMLCostFP()); // False Positive Cost (clean as buggy) - from config
                costMatrix.setCell(1, 0, Config.getMLCostFN()); // False Negative Cost (buggy as clean) - from config
                costMatrix.setCell(1, 1, 0.0);  // True Positive Cost (buggy as buggy)
                costSensitive.setCostMatrix(costMatrix);

                Classifier classifierForFiltering = costSensitive;
                String modelDescription = baseModelName + " (Cost-Sensitive)";
                // --- END: COST-SENSITIVE CLASSIFICATION SETUP ---
                
                Classifier finalClassifier = classifierForFiltering;
                
                // Apply class balancing if enabled in config
                if (Config.isMLBalanceClasses()) {
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    
                    Normalize normalizeFilter = new Normalize();
                    
                    Object balancingFilter = null;
                    String filterName = "SMOTE";
                    
                    try {
                        Class<?> smoteClass = Class.forName("weka.filters.supervised.instance.SMOTE");
                        balancingFilter = smoteClass.getDeclaredConstructor().newInstance();
                        System.out.println("Using SMOTE filter for advanced class balancing");
                    } catch (Exception smoteEx) {
                        balancingFilter = new ClassBalancer();
                        filterName = "ClassBalancer";
                        System.out.println("SMOTE not available, using ClassBalancer as fallback");
                    }
                    
                    MultiFilter multiFilter = new MultiFilter();
                    Filter[] filters = {normalizeFilter, (Filter) balancingFilter};
                    multiFilter.setFilters(filters);
                    
                    filteredClassifier.setFilter(multiFilter);
                    filteredClassifier.setClassifier(classifierForFiltering);
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized + Balanced with " + filterName + ")";
                } else {
                    FilteredClassifier filteredClassifier = new FilteredClassifier();
                    Normalize normalizeFilter = new Normalize();
                    filteredClassifier.setFilter(normalizeFilter);
                    filteredClassifier.setClassifier(classifierForFiltering);
                    finalClassifier = filteredClassifier;
                    modelDescription = baseModelName + " (Cost-Sensitive + Normalized)";
                    System.out.println("Class balancing disabled in config, applying normalization only");
                }
                
                // Perform cross-validation with appropriate number of folds
                int numFolds = Math.min(10, allData.numInstances());
                Evaluation eval = new Evaluation(allData);
                
                if (numFolds < 2) {
                    // For single instance or no data, use train-test split (70-30)
                    System.out.print("Performing train-test evaluation for " + modelDescription + " (insufficient data for CV)... ");
                    long startTime = System.currentTimeMillis();
                    
                    // Use a simple train-test split for very small datasets
                    allData.randomize(new Random(1));
                    int trainSize = Math.max(1, (int) (allData.numInstances() * 0.7));
                    Instances trainData = new Instances(allData, 0, trainSize);
                    Instances testData = new Instances(allData, trainSize, allData.numInstances() - trainSize);
                    
                    if (testData.numInstances() == 0) {
                        // If no test data, train and test on the same data
                        finalClassifier.buildClassifier(trainData);
                        eval.evaluateModel(finalClassifier, trainData);
                    } else {
                        finalClassifier.buildClassifier(trainData);
                        eval.evaluateModel(finalClassifier, testData);
                    }
                    
                    long evalTime = System.currentTimeMillis() - startTime;
                    System.out.println("✓ Completed in " + evalTime + "ms");
                } else {
                    System.out.print("Performing " + numFolds + "-fold cross-validation for " + modelDescription + "... ");
                    long startTime = System.currentTimeMillis();
                    
                    eval.crossValidateModel(finalClassifier, allData, numFolds, new Random(1));
                    
                    long evalTime = System.currentTimeMillis() - startTime;
                    System.out.println("✓ Completed in " + evalTime + "ms");
                }
                
                // --- START: MODIFIED EVALUATION AND REPORTING BLOCK ---
                // Calculate effort-aware metrics for cross-validation
                Map<String, Double> effortMetrics;
                try {
                    effortMetrics = calculateEffortAwareForCrossValidation(finalClassifier, allData, numFolds);
                    System.out.printf("  - Recall@20%%Effort: %.4f%n", effortMetrics.getOrDefault("Recall@20%Effort", 0.0));
                    System.out.printf("  - Popt (Optimal): %.4f%n", effortMetrics.getOrDefault("Popt", 0.0));
                } catch (Exception e) {
                    System.err.println("  Could not calculate effort-aware metrics for CV: " + e.getMessage());
                    effortMetrics = new HashMap<>();
                    effortMetrics.put("Recall@20%Effort", 0.0);
                    effortMetrics.put("Popt", 0.0);
                }
                
                // Bundle all results into our DTO
                ModelEvaluationResult combinedResult = new ModelEvaluationResult(eval, effortMetrics);

                // Store evaluation results for report generation
                resultsMap.put(baseModelName, combinedResult);
                // --- END: MODIFIED EVALUATION AND REPORTING BLOCK ---
                
                // Print detailed validation results
                printCrossValidationResults(modelDescription, eval);
                
            } catch (Exception e) {
                System.err.println("✗ Error evaluating " + baseModelName + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        System.out.println("\n" + "=".repeat(70));
        System.out.println("10-FOLD CROSS-VALIDATION COMPLETED");
        System.out.println("=".repeat(70));
        
        return resultsMap;
    }

    /**
     * Calculates effort-aware metrics for cross-validation by manually performing k-fold CV
     * and collecting predictions across all folds for effort-aware evaluation.
     * 
     * @param classifier The trained classifier
     * @param data The full dataset
     * @param numFolds Number of folds for cross-validation
     * @return Map containing effort-aware metrics
     * @throws Exception if calculation fails
     */
    private Map<String, Double> calculateEffortAwareForCrossValidation(Classifier classifier, Instances data, int numFolds) throws Exception {
        Map<String, Double> results = new HashMap<>();
        
        // Check for required NCSS_CLASS attribute
        Attribute ncssAttribute = data.attribute("NCSS_CLASS");
        if (ncssAttribute == null) {
            System.err.println("Warning: NCSS_CLASS attribute not found. Effort-aware metrics will be 0.");
            results.put("Recall@20%Effort", 0.0);
            results.put("Popt", 0.0);
            return results;
        }
        
        // Prepare data structures for collecting predictions across all folds
        List<EffortAwarePrediction> allPredictions = new ArrayList<>();
        
        // Shuffle data
        data.randomize(new Random(1));
        
        // Stratify if possible
        if (data.classAttribute().isNominal()) {
            data.stratify(numFolds);
        }
        
        int buggyClassIndex = data.classAttribute().value(0).equals("buggy") ? 0 : 1;
        
        // Perform manual cross-validation to collect predictions
        for (int fold = 0; fold < numFolds; fold++) {
            Instances trainSet = data.trainCV(numFolds, fold);
            Instances testSet = data.testCV(numFolds, fold);
            
            // Clone and train classifier for this fold
            Classifier foldClassifier = AbstractClassifier.makeCopy(classifier);
            foldClassifier.buildClassifier(trainSet);
            
            // Collect predictions for this fold
            for (int i = 0; i < testSet.numInstances(); i++) {
                weka.core.Instance instance = testSet.instance(i);
                double[] distribution = foldClassifier.distributionForInstance(instance);
                double buggyProbability = distribution[buggyClassIndex];
                boolean actuallyBuggy = (instance.classValue() == buggyClassIndex);
                double effort = instance.value(ncssAttribute);
                
                allPredictions.add(new EffortAwarePrediction(buggyProbability, actuallyBuggy, effort));
            }
        }
        
        // Now calculate effort-aware metrics from all collected predictions
        if (allPredictions.isEmpty()) {
            results.put("Recall@20%Effort", 0.0);
            results.put("Popt", 0.0);
            return results;
        }
        
        // Calculate total effort and bugs
        double totalEffort = allPredictions.stream().mapToDouble(p -> p.effort).sum();
        long totalBugs = allPredictions.stream().mapToLong(p -> p.actuallyBuggy ? 1 : 0).sum();
        
        if (totalBugs == 0 || totalEffort == 0) {
            results.put("Recall@20%Effort", 0.0);
            results.put("Popt", 0.0);
            return results;
        }
        
        // Sort by probability (descending) - highest probability first
        allPredictions.sort((a, b) -> Double.compare(b.probability, a.probability));
        
        // Calculate effort-aware metrics
        double cumulativeEffort = 0;
        long cumulativeBugsFound = 0;
        double recallAt20Effort = 0.0;
        double maxPopt = 0.0;
        boolean found20Effort = false;
        
        for (EffortAwarePrediction prediction : allPredictions) {
            cumulativeEffort += prediction.effort;
            if (prediction.actuallyBuggy) {
                cumulativeBugsFound++;
            }
            
            double effortRatio = cumulativeEffort / totalEffort;
            double recallRatio = (double) cumulativeBugsFound / totalBugs;
            
            // Calculate Popt (maximum difference between recall ratio and effort ratio)
            double poptValue = recallRatio - effortRatio;
            if (poptValue > maxPopt) {
                maxPopt = poptValue;
            }
            
            // Calculate Recall@20%Effort
            if (!found20Effort && effortRatio >= 0.20) {
                recallAt20Effort = recallRatio;
                found20Effort = true;
            }
        }
        
        results.put("Recall@20%Effort", recallAt20Effort);
        results.put("Popt", maxPopt);
        
        return results;
    }
    
    /**
     * Helper class for effort-aware prediction data
     */
    private static class EffortAwarePrediction {
        final double probability;
        final boolean actuallyBuggy;
        final double effort;
        
        EffortAwarePrediction(double probability, boolean actuallyBuggy, double effort) {
            this.probability = probability;
            this.actuallyBuggy = actuallyBuggy;
            this.effort = effort;
        }
    }
}
