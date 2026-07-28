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
 * Generates results/age_ablation/age_ablation_raw.csv.
 *
 * Verified by re-running all 180 cells (3 projects x 2 variants x 30 seeds)
 * against the published age_ablation_raw.csv: exact match on every field
 * (TP, FP, TN, FN, Precision, Recall, F1, MCC) for every cell.
 *
 * Identical pipeline to SignificanceExperiment.java (CostSensitiveClassifier
 * 10:1 wrapping RandomForest-100, 10-fold CV, no SMOTE, seed varies both the
 * RandomForest seed and the CV fold assignment), applied to two feature sets
 * with AGE removed: Process-minus-AGE (6 process features) and
 * Hybrid-minus-AGE (13 features). Also reports the confusion matrix
 * (TP/FP/TN/FN) in addition to Precision/Recall/F1/MCC, matching the
 * published CSV's columns.
 *
 * Usage: java org.tymz.main.AgeAblationExperiment <project> <process|hybrid> <seed>
 * Prints one CSV row to stdout: project,variant,seed,tp,fp,tn,fn,precision,recall,f1,mcc
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class AgeAblationExperiment {

    private static final String[] PROCESS_MINUS_AGE = {
        "NR", "NDEV", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    private static final String[] HYBRID_MINUS_AGE = {
        "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM",
        "NR", "NDEV", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    private static final String BASE_DIR = "../jml-research/datasets/JML-BugDB-v1.1/arff/";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: AgeAblationExperiment <project> <process|hybrid> <seed>");
            System.exit(1);
        }
        String project = args[0];
        String variantArg = args[1];
        int seed = Integer.parseInt(args[2]);

        String[] features;
        String variantLabel;
        switch (variantArg) {
            case "process": features = PROCESS_MINUS_AGE; variantLabel = "Process-minus-AGE"; break;
            case "hybrid": features = HYBRID_MINUS_AGE; variantLabel = "Hybrid-minus-AGE"; break;
            default:
                System.err.println("Unknown variant: " + variantArg);
                System.exit(1);
                return;
        }

        Instances originalData = loadDataset(BASE_DIR + project + "-dataset.arff");
        EvaluationResult result = evaluateFeatureSet(originalData, features, seed);

        System.out.printf(Locale.US, "%s,%s,%d,%.1f,%.1f,%.1f,%.1f,%.4f,%.4f,%.4f,%.4f%n",
            project, variantLabel, seed,
            result.tp, result.fp, result.tn, result.fn,
            result.precision, result.recall, result.f1Score, result.mcc);
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
            eval.numTruePositives(positiveClassIndex),
            eval.numFalsePositives(positiveClassIndex),
            eval.numTrueNegatives(positiveClassIndex),
            eval.numFalseNegatives(positiveClassIndex),
            eval.precision(positiveClassIndex),
            eval.recall(positiveClassIndex),
            eval.fMeasure(positiveClassIndex),
            eval.matthewsCorrelationCoefficient(positiveClassIndex)
        );
    }

    private static class EvaluationResult {
        final double tp, fp, tn, fn;
        final double precision, recall, f1Score, mcc;
        EvaluationResult(double tp, double fp, double tn, double fn,
                          double precision, double recall, double f1Score, double mcc) {
            this.tp = tp; this.fp = fp; this.tn = tn; this.fn = fn;
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.mcc = mcc;
        }
    }
}
