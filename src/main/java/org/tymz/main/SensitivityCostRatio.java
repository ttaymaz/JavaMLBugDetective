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
 * Cost-ratio sensitivity sweep on the same pipeline as FeatureAblationStudy.java
 * and SignificanceExperiment.java: canonical 14 features, CostSensitiveClassifier
 * (varying only the FN:FP cost matrix), no SMOTE, RandomForest-100, 10-fold CV.
 *
 * Usage: java org.tymz.main.SensitivityCostRatio <project> <costRatio>
 * Prints one CSV row to stdout: project,costRatio,precision,recall,f1,mcc
 *
 * @author Turgay TAYMAZ
 */
public class SensitivityCostRatio {

    private static final String[] ALL_FEATURES = {
        "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM",
        "NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    private static final String BASE_DIR = "../jml-research/datasets/JML-BugDB-v1.1/arff/";
    private static final int CV_SEED = 42; // matches FeatureAblationStudy.java's verification seed

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: SensitivityCostRatio <project> <costRatio>");
            System.exit(1);
        }
        String project = args[0];
        double costRatio = Double.parseDouble(args[1]);

        Instances originalData = loadDataset(BASE_DIR + project + "-dataset.arff");
        EvaluationResult result = evaluate(originalData, costRatio);

        System.out.printf(Locale.US, "%s,%.1f,%.4f,%.4f,%.4f,%.4f%n",
            project, costRatio, result.precision, result.recall, result.f1Score, result.mcc);
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

    private static EvaluationResult evaluate(Instances originalData, double fnCost) throws Exception {
        Instances data = new Instances(originalData);

        String[] featuresToKeep = Arrays.copyOf(ALL_FEATURES, ALL_FEATURES.length + 1);
        featuresToKeep[ALL_FEATURES.length] = data.classAttribute().name();

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
        rf.setSeed(CV_SEED);

        CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
        costSensitive.setClassifier(rf);

        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0);
        costMatrix.setCell(0, 1, 1.0);
        costMatrix.setCell(1, 0, fnCost);
        costMatrix.setCell(1, 1, 0.0);
        costSensitive.setCostMatrix(costMatrix);

        Evaluation eval = new Evaluation(data);
        eval.crossValidateModel(costSensitive, data, 10, new Random(CV_SEED));

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
