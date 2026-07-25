package org.tymz.ml;

import weka.classifiers.Evaluation;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;
import weka.classifiers.CostMatrix;
import weka.filters.MultiFilter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.supervised.instance.SMOTE;

import java.util.Locale;
import java.util.Random;

/**
 * Sensitivity analysis for cost ratio and SMOTE k-parameter sweeps.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * Resolves the buggy-class index dynamically from the dataset's own attribute
 * declaration (matching ModelTrainer's approach) rather than assuming a fixed
 * index, loads the frozen, paper-canonical ARFF datasets, and uses a
 * randomized, class-stratified 80/20 split.
 *
 * Single-cell CLI entry point, intended to be fanned out across processes by
 * an external driver script (a single RandomForest(100 trees, unbounded
 * depth) fit on the Kafka dataset takes ~14 CPU-minutes in this Weka version,
 * which has no built-in multi-threading; running the full parameter grid
 * serially in one JVM is impractically slow, so parallelism is done at the
 * process level instead).
 *
 * Usage: java org.tymz.ml.SensitivityAnalysis <cost|smote> <project> <paramValue>
 *   e.g. java org.tymz.ml.SensitivityAnalysis cost kafka 10.0
 *        java org.tymz.ml.SensitivityAnalysis smote gson 5
 * Prints one CSV row to stdout: project,paramValue,precision,recall,f1,mcc
 */
public class SensitivityAnalysis {

    // Frozen, paper-canonical datasets (byte-identical to zenodo_package/datasets/*.csv).
    // Do NOT point this at a freshly re-mined dataset -- row counts are not
    // guaranteed to match a live re-mine of the source repositories.
    private static final String BASE_DIR = "../jml-research/datasets/JML-BugDB-v1.1/arff/";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: SensitivityAnalysis <cost|smote> <project> <paramValue>");
            System.exit(1);
        }
        String mode = args[0];
        String project = args[1];
        String paramArg = args[2];

        Instances data = loadProject(project);
        int buggyIdx = resolveBuggyIndex(data);
        Instances[] split = stratifiedSplit(data, 1);

        double fnCost;
        int smoteK;
        String paramLabel;
        if (mode.equals("cost")) {
            fnCost = Double.parseDouble(paramArg);
            smoteK = 5;
            paramLabel = String.format(Locale.US, "%.1f", fnCost);
        } else if (mode.equals("smote")) {
            fnCost = 10.0;
            smoteK = Integer.parseInt(paramArg);
            paramLabel = String.valueOf(smoteK);
        } else {
            System.err.println("Unknown mode: " + mode);
            System.exit(1);
            return;
        }

        Evaluation eval = runModel(split[0], split[1], fnCost, smoteK, buggyIdx);
        double precision = safe(eval.precision(buggyIdx));
        double recall = safe(eval.recall(buggyIdx));
        double f1 = safe(eval.fMeasure(buggyIdx));
        double mcc = safe(eval.matthewsCorrelationCoefficient(buggyIdx));

        System.out.printf(Locale.US, "%s,%s,%.4f,%.4f,%.4f,%.4f%n",
                project, paramLabel, precision, recall, f1, mcc);
    }

    private static Instances loadProject(String project) throws Exception {
        DataSource source = new DataSource(BASE_DIR + project + "-dataset.arff");
        Instances data = source.getDataSet();
        if (data.classIndex() == -1) {
            data.setClassIndex(data.numAttributes() - 1);
        }
        return data;
    }

    /** Resolve the "buggy" class index from the dataset's own attribute declaration
     *  instead of assuming a fixed index (mirrors ModelTrainer.getBuggyClassIndex()). */
    private static int resolveBuggyIndex(Instances data) {
        for (int i = 0; i < data.classAttribute().numValues(); i++) {
            if ("buggy".equals(data.classAttribute().value(i))) {
                return i;
            }
        }
        return 1; // fallback, matches ModelTrainer's default assumption
    }

    /** Randomized, class-stratified 80/20 split using Weka's stratify(5) + fold 0 as test. */
    private static Instances[] stratifiedSplit(Instances allData, long seed) {
        Instances data = new Instances(allData);
        data.randomize(new Random(seed));
        data.stratify(5);
        Instances test = data.testCV(5, 0);
        Instances train = data.trainCV(5, 0);
        return new Instances[]{train, test};
    }

    private static double safe(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    private static Evaluation runModel(Instances train, Instances test, double fnCost, int smoteK, int buggyIdx) throws Exception {
        int cleanIdx = 1 - buggyIdx;

        RandomForest rf = new RandomForest();
        rf.setNumIterations(100);
        rf.setSeed(1);

        CostSensitiveClassifier costSensitive = new CostSensitiveClassifier();
        costSensitive.setClassifier(rf);
        costSensitive.setMinimizeExpectedCost(true);

        CostMatrix matrix = new CostMatrix(2);
        matrix.setCell(cleanIdx, cleanIdx, 0.0);
        matrix.setCell(cleanIdx, buggyIdx, 1.0);     // FP cost
        matrix.setCell(buggyIdx, cleanIdx, fnCost);  // FN cost
        matrix.setCell(buggyIdx, buggyIdx, 0.0);
        costSensitive.setCostMatrix(matrix);

        int numBuggy = 0, numClean = 0;
        for (int i = 0; i < train.numInstances(); i++) {
            if (((int) train.instance(i).classValue()) == buggyIdx) numBuggy++;
            else numClean++;
        }

        Normalize normalize = new Normalize();
        SMOTE smote = new SMOTE();
        smote.setNearestNeighbors(smoteK);
        if (numBuggy > 0 && numClean > numBuggy) {
            double percentage = ((double) (numClean - numBuggy) / numBuggy) * 100.0;
            smote.setPercentage(percentage);
        } else {
            smote.setPercentage(0);
        }

        MultiFilter multiFilter = new MultiFilter();
        multiFilter.setFilters(new weka.filters.Filter[]{normalize, smote});

        weka.classifiers.meta.FilteredClassifier fc = new weka.classifiers.meta.FilteredClassifier();
        fc.setFilter(multiFilter);
        fc.setClassifier(costSensitive);

        fc.buildClassifier(train);

        Evaluation eval = new Evaluation(train);
        eval.evaluateModel(fc, test);
        return eval;
    }
}
