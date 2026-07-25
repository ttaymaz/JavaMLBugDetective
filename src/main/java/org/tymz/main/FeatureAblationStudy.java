package org.tymz.main;

import weka.classifiers.Evaluation;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.classifiers.CostMatrix;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Random;

/**
 * Feature Ablation Study for the JavaMLBugDetective methodology paper.
 *
 * Compares three feature configurations (Static-only, Process-only, and the
 * full 14-feature Hybrid model) across three projects using RandomForest with
 * cost-sensitive learning and 10-fold cross-validation. Reports Precision,
 * Recall, F1, and MCC per project and feature set.
 *
 * Feature set definitions: 14 named features (7 static code metrics via the
 * Remove filter, 7 process metrics), CostSensitiveClassifier with a 10:1 cost
 * matrix, RandomForest (100 trees, seed=42), 10-fold CV (seed=42), dynamic
 * positive-class-index resolution, no SMOTE.
 *
 * Reads the frozen, paper-canonical ARFF dataset snapshot at
 * jml-research/datasets/JML-BugDB-v1.1/arff/.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class FeatureAblationStudy {

    private static final String[] STATIC_FEATURES = {
        "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM"
    };

    private static final String[] PROCESS_FEATURES = {
        "NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    private static final String[] ALL_FEATURES = {
        "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM",
        "NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    public static void main(String[] args) {
        String[] datasetPaths = {
            "../jml-research/datasets/JML-BugDB-v1.1/arff/gson-dataset.arff",
            "../jml-research/datasets/JML-BugDB-v1.1/arff/kafka-dataset.arff",
            "../jml-research/datasets/JML-BugDB-v1.1/arff/commons-io-dataset.arff"
        };

        String[] projectNames = {"Gson", "Kafka", "Commons-IO"};

        try {
            System.out.println("=================================================================");
            System.out.println("   CROSS-PROJECT FEATURE ABLATION STUDY (verification re-run)");
            System.out.println("=================================================================");
            System.out.println("Algorithm: RandomForest(100, seed=42) + CostSensitiveClassifier (10:1)");
            System.out.println("Evaluation: 10-Fold Cross-Validation (seed=42), no SMOTE");
            System.out.println("Projects: Gson, Kafka, Commons-IO");
            System.out.println("-----------------------------------------------------------------\n");

            java.util.Map<String, java.util.Map<String, EvaluationResult>> allResults =
                new java.util.LinkedHashMap<>();

            for (int i = 0; i < datasetPaths.length; i++) {
                String projectName = projectNames[i];
                String arffFilePath = datasetPaths[i];

                System.out.println("\n>>> PROCESSING PROJECT: " + projectName);
                File datasetFile = new File(arffFilePath);
                if (!datasetFile.exists()) {
                    System.err.println("ERROR: Dataset file not found: " + arffFilePath);
                    continue;
                }

                Instances originalData = loadDataset(arffFilePath);
                System.out.println("  Instances: " + originalData.numInstances() + ", Attributes: " + originalData.numAttributes());

                java.util.Map<String, EvaluationResult> projectResults =
                    performAblationStudyForProject(originalData, projectName);
                allResults.put(projectName, projectResults);
            }

            printConsolidatedResults(allResults);

        } catch (Exception e) {
            System.err.println("Error during ablation study execution:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Instances loadDataset(String filePath) throws Exception {
        ArffLoader loader = new ArffLoader();
        loader.setSource(new FileInputStream(new File(filePath)));
        Instances data = loader.getDataSet();
        if (data.classIndex() == -1) {
            for (int i = 0; i < data.numAttributes(); i++) {
                if (data.attribute(i).name().toLowerCase().contains("buggy")) {
                    data.setClassIndex(i);
                    break;
                }
            }
            if (data.classIndex() == -1) {
                data.setClassIndex(data.numAttributes() - 1);
            }
        }
        return data;
    }

    private static java.util.Map<String, EvaluationResult> performAblationStudyForProject(
            Instances originalData, String projectName) throws Exception {
        ExperimentConfig[] experiments = {
            new ExperimentConfig("Hybrid (Full)", ALL_FEATURES),
            new ExperimentConfig("Process-Only", PROCESS_FEATURES),
            new ExperimentConfig("Static-Only", STATIC_FEATURES)
        };

        java.util.Map<String, EvaluationResult> results = new java.util.LinkedHashMap<>();

        System.out.println("EXPERIMENTAL RESULTS FOR " + projectName + ":");
        System.out.printf("%-20s | %-9s | %-9s | %-9s | %-9s%n",
                         "Model", "Precision", "Recall", "F1-Score", "MCC");

        for (ExperimentConfig config : experiments) {
            EvaluationResult result = evaluateFeatureSet(originalData, config);
            results.put(config.name, result);

            System.out.printf("%-20s | %-9.3f | %-9.3f | %-9.3f | %-9.3f%n",
                            config.name, result.precision, result.recall, result.f1Score, result.mcc);
        }
        System.out.println();
        return results;
    }

    private static void printConsolidatedResults(
            java.util.Map<String, java.util.Map<String, EvaluationResult>> allResults) {
        System.out.println("\n=================================================================");
        System.out.println("   CONSOLIDATED RESULTS - CSV FORMAT");
        System.out.println("=================================================================\n");
        System.out.println("Project,FeatureSet,Precision,Recall,F1,MCC");
        for (java.util.Map.Entry<String, java.util.Map<String, EvaluationResult>> projectEntry : allResults.entrySet()) {
            for (java.util.Map.Entry<String, EvaluationResult> resultEntry : projectEntry.getValue().entrySet()) {
                EvaluationResult r = resultEntry.getValue();
                System.out.printf(java.util.Locale.US, "%s,%s,%.4f,%.4f,%.4f,%.4f%n",
                    projectEntry.getKey(), resultEntry.getKey(), r.precision, r.recall, r.f1Score, r.mcc);
            }
        }
    }

    private static EvaluationResult evaluateFeatureSet(Instances originalData, ExperimentConfig config) throws Exception {
        Instances data = new Instances(originalData);

        String[] featuresToKeep = Arrays.copyOf(config.features, config.features.length + 1);
        featuresToKeep[config.features.length] = data.classAttribute().name();

        StringBuilder indicesToRemove = new StringBuilder();
        for (int i = 0; i < data.numAttributes(); i++) {
            String attrName = data.attribute(i).name();
            boolean shouldKeep = false;
            for (String featureToKeep : featuresToKeep) {
                if (attrName.equals(featureToKeep)) {
                    shouldKeep = true;
                    break;
                }
            }
            if (!shouldKeep) {
                if (indicesToRemove.length() > 0) indicesToRemove.append(",");
                indicesToRemove.append(String.valueOf(i + 1));
            }
        }

        if (indicesToRemove.length() > 0) {
            Remove removeFilter = new Remove();
            removeFilter.setAttributeIndices(indicesToRemove.toString());
            removeFilter.setInputFormat(data);
            data = Filter.useFilter(data, removeFilter);
        }

        RandomForest rf = new RandomForest();
        rf.setNumIterations(100);
        rf.setSeed(42);

        CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
        costSensitive.setClassifier(rf);

        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0);
        costMatrix.setCell(0, 1, 1.0);
        costMatrix.setCell(1, 0, 10.0);
        costMatrix.setCell(1, 1, 0.0);
        costSensitive.setCostMatrix(costMatrix);

        Evaluation eval = new Evaluation(data);
        eval.crossValidateModel(costSensitive, data, 10, new Random(42));

        int positiveClassIndex = 1;
        if (data.classAttribute().value(0).toLowerCase().contains("true") ||
            data.classAttribute().value(0).toLowerCase().contains("buggy")) {
            positiveClassIndex = 0;
        }

        return new EvaluationResult(
            eval.precision(positiveClassIndex),
            eval.recall(positiveClassIndex),
            eval.fMeasure(positiveClassIndex),
            eval.matthewsCorrelationCoefficient(positiveClassIndex)
        );
    }

    private static class ExperimentConfig {
        final String name;
        final String[] features;
        ExperimentConfig(String name, String[] features) {
            this.name = name;
            this.features = features;
        }
    }

    private static class EvaluationResult {
        final double precision;
        final double recall;
        final double f1Score;
        final double mcc;
        EvaluationResult(double precision, double recall, double f1Score, double mcc) {
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.mcc = mcc;
        }
    }
}
