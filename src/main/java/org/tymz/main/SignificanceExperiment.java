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
import java.util.Locale;
import java.util.Random;

/**
 * 30-seed significance experiment: single-cell CLI runner, fanned out across
 * processes by an external driver script (see run_significance_parallel.sh).
 *
 * Same methodology as FeatureAblationStudy.java (the verified source of the
 * manuscript's ablation table), varied only by seed, so the ablation table,
 * Wilcoxon/Cliff's-delta significance tests, and sensitivity-analysis anchor
 * all trace to the same logged, reproducible pipeline:
 *   - 14 named features (Static: WMC/CBO/RFC/LCOM/TCC/NCSS_CLASS/CYCLO_SUM;
 *     Process: NR/NDEV/AGE/EXP/LINES_ADDED/LINES_DELETED/HUNK_COUNT)
 *   - CostSensitiveClassifier, 10:1 cost matrix (FN:FP), no SMOTE
 *   - RandomForest(100 trees), 10-fold cross-validation
 *   - Frozen, paper-canonical ARFF datasets (jml-research/datasets/JML-BugDB-v1.1/arff/)
 *
 * Seed varies BOTH the RandomForest's internal seed and the CV fold-assignment
 * seed (there is no SMOTE component to vary in this verified pipeline).
 *
 * Usage: java org.tymz.main.SignificanceExperiment <project> <hybrid|process|static> <seed>
 * Prints one CSV row to stdout: project,featureset,seed,precision,recall,f1,mcc
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class SignificanceExperiment {

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

    private static final String BASE_DIR = "../jml-research/datasets/JML-BugDB-v1.1/arff/";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: SignificanceExperiment <project> <hybrid|process|static> <seed>");
            System.exit(1);
        }
        String project = args[0];
        String featureSetArg = args[1];
        int seed = Integer.parseInt(args[2]);

        String[] features;
        String featureSetLabel;
        switch (featureSetArg) {
            case "hybrid": features = ALL_FEATURES; featureSetLabel = "Hybrid"; break;
            case "process": features = PROCESS_FEATURES; featureSetLabel = "Process"; break;
            case "static": features = STATIC_FEATURES; featureSetLabel = "Static"; break;
            default:
                System.err.println("Unknown feature set: " + featureSetArg);
                System.exit(1);
                return;
        }

        Instances originalData = loadDataset(BASE_DIR + project + "-dataset.arff");
        EvaluationResult result = evaluateFeatureSet(originalData, features, seed);

        System.out.printf(Locale.US, "%s,%s,%d,%.4f,%.4f,%.4f,%.4f%n",
            project, featureSetLabel, seed, result.precision, result.recall, result.f1Score, result.mcc);
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

    private static EvaluationResult evaluateFeatureSet(Instances originalData, String[] features, int seed) throws Exception {
        Instances data = new Instances(originalData);

        String[] featuresToKeep = Arrays.copyOf(features, features.length + 1);
        featuresToKeep[features.length] = data.classAttribute().name();

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
        rf.setSeed(seed);

        CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
        costSensitive.setClassifier(rf);

        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0);
        costMatrix.setCell(0, 1, 1.0);
        costMatrix.setCell(1, 0, 10.0);
        costMatrix.setCell(1, 1, 0.0);
        costSensitive.setCostMatrix(costMatrix);

        Evaluation eval = new Evaluation(data);
        eval.crossValidateModel(costSensitive, data, 10, new Random(seed));

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

    private static class EvaluationResult {
        final double precision, recall, f1Score, mcc;
        EvaluationResult(double precision, double recall, double f1Score, double mcc) {
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.mcc = mcc;
        }
    }
}
