package org.tymz.main;

import weka.classifiers.Evaluation;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.classifiers.CostMatrix;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Reconstruction for results/noise_sensitivity/noise_sensitivity_raw.csv.
 *
 * This is a reconstruction against the documented specification, not the
 * original artifact. The script used during this engagement's earlier
 * Commons-IO saturation diagnosis (also named NoiseSensitivityExperiment.java)
 * lived in a previous session's scratchpad directory, which no longer
 * exists on disk; it was never committed to either repository. Only its
 * documented behaviour survives (recorded verbatim in this project's
 * session notes and reflected in the manuscript's Section 4.6): the flip
 * injection is symmetric and prevalence-preserving, k = round(noiseLevel *
 * N / 2) instances relabelled in each direction, silently capped at
 * min(#positives, #negatives) with no error and no sampling with
 * replacement -- the cap that causes Commons-IO's minority class to
 * saturate at a noise level of 41.7%, well inside the swept 0-50% range.
 * That cap is implemented and left visible below, not smoothed over.
 *
 * This reconstruction reproduces the documented noise level and the
 * saturation mechanism exactly (verified: realised class counts match the
 * design exactly at every level), but selects a different specific set of
 * instances to flip than whatever produced the original archive, so it
 * does not reproduce the previous archive's per-cell values. Rather than
 * ship a script that doesn't regenerate the data beside it,
 * noise_sensitivity_raw.csv and noise_sensitivity_summary.csv were
 * regenerated from this script's own output for every NoiseLevel > 0 cell
 * (450 of 540 rows); NoiseLevel = 0.0 rows (90 of 540) are reused from
 * significance_raw_results.csv, not run by this script -- see
 * results/README.md for why. The manuscript's noise-sensitivity
 * conclusions were checked against this regenerated data and hold
 * unchanged; see results/README.md for the comparison.
 *
 * Same base pipeline as SignificanceExperiment.java (CostSensitiveClassifier
 * 10:1 wrapping RandomForest-100, 10-fold CV, no SMOTE), with noise applied
 * to the class labels before cross-validation. The seed selects which
 * instances flip (via Collections.shuffle, independently for the positive
 * and negative subsets, so the direction of each flip is unambiguous) and
 * is reused for the RandomForest seed and the CV fold-assignment seed.
 *
 * Usage: java org.tymz.main.NoiseSensitivityExperiment <project> <hybrid|process|static> <noiseLevel> <seed>
 * Prints one CSV row to stdout: project,featureset,noiselevel,seed,tp,fp,tn,fn,precision,recall,f1,mcc
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class NoiseSensitivityExperiment {

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
        if (args.length != 4) {
            System.err.println("Usage: NoiseSensitivityExperiment <project> <hybrid|process|static> <noiseLevel> <seed>");
            System.exit(1);
        }
        String project = args[0];
        String featureSetArg = args[1];
        double noiseLevel = Double.parseDouble(args[2]);
        int seed = Integer.parseInt(args[3]);

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
        EvaluationResult result = evaluateFeatureSet(originalData, features, noiseLevel, seed);

        System.out.printf(Locale.US, "%s,%s,%s,%d,%.10f,%.10f,%.10f,%.10f,%.4f,%.4f,%.4f,%.4f%n",
            project, featureSetLabel, formatLevel(noiseLevel), seed,
            result.tp, result.fp, result.tn, result.fn,
            result.precision, result.recall, result.f1Score, result.mcc);
    }

    private static String formatLevel(double noiseLevel) {
        return String.format(Locale.US, "%.1f", noiseLevel);
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

    /**
     * Symmetric, prevalence-preserving label-flip injection. k = round(noiseLevel * N / 2)
     * instances are relabelled in each direction (positive-to-negative and
     * negative-to-positive), so overall class balance is unchanged. k is
     * silently capped at the size of the smaller class in each direction --
     * this is the cap responsible for Commons-IO's minority-class saturation
     * at 41.7% noise, documented in Section 4.6 of the manuscript.
     */
    private static void injectNoise(Instances data, double noiseLevel, int seed, int positiveClassIndex) {
        if (noiseLevel <= 0.0) {
            return;
        }
        int n = data.numInstances();
        int k = (int) Math.round(noiseLevel * n / 2.0);

        List<Integer> positiveIdx = new ArrayList<>();
        List<Integer> negativeIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instance inst = data.instance(i);
            if ((int) inst.classValue() == positiveClassIndex) {
                positiveIdx.add(i);
            } else {
                negativeIdx.add(i);
            }
        }

        int kPosToNeg = Math.min(k, positiveIdx.size());
        int kNegToPos = Math.min(k, negativeIdx.size());

        Collections.shuffle(positiveIdx, new Random(seed));
        Collections.shuffle(negativeIdx, new Random(seed));

        int negativeClassIndex = 1 - positiveClassIndex;
        for (int i = 0; i < kPosToNeg; i++) {
            data.instance(positiveIdx.get(i)).setClassValue(negativeClassIndex);
        }
        for (int i = 0; i < kNegToPos; i++) {
            data.instance(negativeIdx.get(i)).setClassValue(positiveClassIndex);
        }
    }

    private static EvaluationResult evaluateFeatureSet(Instances originalData, String[] features,
                                                         double noiseLevel, int seed) throws Exception {
        Instances data = new Instances(originalData);

        int positiveClassIndex = 1;
        if (data.classAttribute().value(0).toLowerCase().contains("true") ||
            data.classAttribute().value(0).toLowerCase().contains("buggy")) {
            positiveClassIndex = 0;
        }

        injectNoise(data, noiseLevel, seed, positiveClassIndex);

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
