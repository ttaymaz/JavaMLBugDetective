package org.tymz.main;

import weka.classifiers.Evaluation;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.filters.Filter;
import weka.filters.MultiFilter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.supervised.instance.SMOTE;
import weka.classifiers.CostMatrix;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Empirical comparison of CostSensitiveClassifier alone vs.
 * CostSensitiveClassifier + SMOTE. Same base pipeline as
 * FeatureAblationStudy.java (canonical 14 features, RandomForest-100, 10:1
 * cost matrix, 10-fold CV, seed=42), varied only by adding or omitting a
 * SMOTE (k=5) + Normalize filter chain ahead of the classifier.
 *
 * Usage: java org.tymz.main.SmoteComparisonStudy <project> <cs-only|cs-smote>
 * Prints one CSV row to stdout: project,variant,precision,recall,f1,mcc
 *
 * @author Turgay TAYMAZ
 */
public class SmoteComparisonStudy {

    private static final String[] ALL_FEATURES = {
        "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM",
        "NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };

    private static final String BASE_DIR = "../jml-research/datasets/JML-BugDB-v1.1/arff/";
    private static final int SEED = 42;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: SmoteComparisonStudy <project> <cs-only|cs-smote>");
            System.exit(1);
        }
        String project = args[0];
        String variant = args[1];
        boolean useSmote = variant.equals("cs-smote");

        Instances originalData = loadDataset(BASE_DIR + project + "-dataset.arff");
        EvaluationResult result = evaluate(originalData, useSmote);

        System.out.printf(Locale.US, "%s,%s,%.4f,%.4f,%.4f,%.4f%n",
            project, variant, result.precision, result.recall, result.f1Score, result.mcc);
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

    private static EvaluationResult evaluate(Instances originalData, boolean useSmote) throws Exception {
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
        rf.setSeed(SEED);

        CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
        CostMatrix costMatrix = new CostMatrix(2);
        costMatrix.setCell(0, 0, 0.0);
        costMatrix.setCell(0, 1, 1.0);
        costMatrix.setCell(1, 0, 10.0);
        costMatrix.setCell(1, 1, 0.0);
        costSensitive.setCostMatrix(costMatrix);

        weka.classifiers.Classifier finalClassifier;

        if (useSmote) {
            int positiveClassIndex = 1;
            if (data.classAttribute().value(0).toLowerCase().contains("true") ||
                data.classAttribute().value(0).toLowerCase().contains("buggy")) {
                positiveClassIndex = 0;
            }
            int numPositive = 0, numOther = 0;
            for (int i = 0; i < data.numInstances(); i++) {
                if (((int) data.instance(i).classValue()) == positiveClassIndex) numPositive++;
                else numOther++;
            }

            Normalize normalize = new Normalize();
            SMOTE smote = new SMOTE();
            smote.setNearestNeighbors(5);
            if (numPositive > 0 && numOther > numPositive) {
                double percentage = ((double) (numOther - numPositive) / numPositive) * 100.0;
                smote.setPercentage(percentage);
            } else {
                smote.setPercentage(0);
            }

            MultiFilter multiFilter = new MultiFilter();
            multiFilter.setFilters(new weka.filters.Filter[]{normalize, smote});

            costSensitive.setClassifier(rf);
            FilteredClassifier fc = new FilteredClassifier();
            fc.setFilter(multiFilter);
            fc.setClassifier(costSensitive);
            finalClassifier = fc;
        } else {
            costSensitive.setClassifier(rf);
            finalClassifier = costSensitive;
        }

        Evaluation eval = new Evaluation(data);
        eval.crossValidateModel(finalClassifier, data, 10, new Random(SEED));

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
