package org.tymz.main;

import weka.classifiers.Evaluation;
import weka.classifiers.trees.RandomForest; // or we can use whatever config uses
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import java.util.Random;

/**
 * Early, single-seed ablation check for Gson only. Superseded by
 * {@link FeatureAblationStudy}, which is the verified, multi-project,
 * multi-seed runner behind the paper's reported ablation results.
 * Retained for reference; not part of the reported pipeline.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class Ablation {
    public static void main(String[] args) throws Exception {
        DataSource source = new DataSource("gson-dataset.arff");
        Instances data = source.getDataSet();
        if (data.classIndex() == -1) {
            data.setClassIndex(data.numAttributes() - 1);
        }

        System.out.println("=== ABLATION STUDY FOR GSON ===");

        // 1. Hybrid (All features)
        runModel(data, "Hybrid (All Features)");

        // 2. Static Only (Remove 1-7,15-17 indices -> 1-7, 15-17 in 1-based, plus class is 18)
        Remove removeProcess = new Remove();
        removeProcess.setAttributeIndices("1-7,15-17");
        removeProcess.setInputFormat(data);
        Instances staticData = Filter.useFilter(data, removeProcess);
        runModel(staticData, "Static-Only (WMC, TCC, RFC, LCOM, CBO, NCSS_CLASS, CYCLO_SUM)");

        // 3. Process Only (Remove 8-14)
        Remove removeStatic = new Remove();
        removeStatic.setAttributeIndices("8-14");
        removeStatic.setInputFormat(data);
        Instances processData = Filter.useFilter(data, removeStatic);
        runModel(processData, "Process-Only (NR, NDEV, AGE, EXP, LINES_ADDED/DELETED, HUNK, etc.)");
    }

    private static void runModel(Instances data, String label) throws Exception {
        RandomForest rf = new RandomForest();
        rf.setNumIterations(100);
        Evaluation eval = new Evaluation(data);
        eval.crossValidateModel(rf, data, 10, new Random(1));
        
        int buggyClassIndex = 1; // Assuming 'buggy' is index 1
        double mcc = eval.matthewsCorrelationCoefficient(buggyClassIndex);
        double f1 = eval.fMeasure(buggyClassIndex);
        double precision = eval.precision(buggyClassIndex);
        double recall = eval.recall(buggyClassIndex);

        System.out.println("--- " + label + " ---");
        System.out.println("Precision: " + precision);
        System.out.println("Recall:    " + recall);
        System.out.println("F1-Score:  " + f1);
        System.out.println("MCC:       " + mcc);
        System.out.println();
    }
}
