package org.tymz.main;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.tymz.config.Config;
import org.tymz.db.DatabaseManager;
import org.tymz.feature.DataPreprocessor;
import org.tymz.git.GitRepositoryManager;
import org.tymz.metric.ProcessMetricsCalculator;
import org.tymz.metric.StaticMetricsCalculator;
import org.tymz.szz.SZZBugLabeler;
import weka.classifiers.Evaluation;
import weka.classifiers.CostMatrix;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Three-phase timed re-run of the reported pipeline (SZZ labeling, feature
 * extraction, CS-only RandomForest training on the 14 canonical features),
 * for Green-AI timing verification. Deliberately does NOT run Main's
 * temporal/version-based validation, latest-version prediction, or the
 * multi-algorithm benchmark (J48/NaiveBayes/SMO/AdaBoostM1) -- only the
 * configuration actually reported in the manuscript (CostSensitiveClassifier
 * 10:1 wrapping RandomForest-100, 10-fold CV, no SMOTE), matching
 * FeatureAblationStudy's training methodology exactly, but timed against a
 * freshly extracted (not frozen-ARFF) dataset.
 *
 * Must be run with the process's working directory set to a throwaway
 * scratch directory containing its own config.properties pointing
 * repository.local.path at an existing local clone and project.name at a
 * database file name that does not exist anywhere else -- DatabaseManager
 * resolves the database path relative to the process's actual working
 * directory, so this is sufficient to guarantee no existing database is
 * touched.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class SzzTimedPipelineRun {

    private static final String[] STATIC_FEATURES = {
            "WMC", "CBO", "RFC", "LCOM", "TCC", "NCSS_CLASS", "CYCLO_SUM"
    };
    private static final String[] PROCESS_FEATURES = {
            "NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"
    };
    private static final String[] ALL_FEATURES;
    static {
        ALL_FEATURES = new String[STATIC_FEATURES.length + PROCESS_FEATURES.length];
        System.arraycopy(STATIC_FEATURES, 0, ALL_FEATURES, 0, STATIC_FEATURES.length);
        System.arraycopy(PROCESS_FEATURES, 0, ALL_FEATURES, STATIC_FEATURES.length, PROCESS_FEATURES.length);
    }

    public static void main(String[] args) throws Exception {
        String projectLabel = args.length > 0 ? args[0] : Config.getProjectName();

        System.out.println("PROJECT=" + projectLabel);
        System.out.println("DB_FILE=" + Config.getDatabaseName() + " (resolved relative to CWD=" + System.getProperty("user.dir") + ")");
        System.out.println("JAVA_VERSION=" + System.getProperty("java.version"));
        System.out.println("OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("AVAILABLE_PROCESSORS=" + Runtime.getRuntime().availableProcessors());
        System.out.println("MAX_HEAP_BYTES=" + Runtime.getRuntime().maxMemory());
        System.out.println("JVM_ARGS=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("CONFIG: ml.cost.fn=" + Config.getMLCostFN() + " ml.cost.fp=" + Config.getMLCostFP()
                + " ml.balance.classes=" + Config.isMLBalanceClasses() + " ml.cv.folds=" + Config.getMLCVFolds());

        DatabaseManager dbManager = null;
        GitRepositoryManager gitManager = null;
        Repository repository = null;
        try {
            dbManager = DatabaseManager.getInstance();
            gitManager = new GitRepositoryManager();
            repository = gitManager.loadOrCloneRepository();

            System.out.println("CLONE_HEAD_SHA=" + repository.resolve("HEAD").getName());

            List<RevCommit> commits = gitManager.getAllCommits(repository);
            System.out.println("TOTAL_COMMITS=" + commits.size());

            // ---- Phase 1: SZZ labeling ----
            long p1Start = System.currentTimeMillis();
            SZZBugLabeler szzLabeler = new SZZBugLabeler(dbManager);
            szzLabeler.labelBugs(repository, commits);
            dbManager.commit();
            long p1Ms = System.currentTimeMillis() - p1Start;
            System.out.printf("PHASE1_SZZ_LABELING_MS=%d%n", p1Ms);
            System.out.println(szzLabeler.getLabelingStats());

            List<RevCommit> chronologicalCommits = new ArrayList<>(commits);
            Collections.reverse(chronologicalCommits);

            // ---- Phase 2: feature extraction (process + static metrics) ----
            long p2Start = System.currentTimeMillis();
            ProcessMetricsCalculator processMetricsCalculator = new ProcessMetricsCalculator(dbManager);
            processMetricsCalculator.calculateAndSaveMetricsForAllCommits(repository);
            dbManager.commit();

            StaticMetricsCalculator staticMetricsCalculator = new StaticMetricsCalculator(dbManager, repository);
            for (RevCommit commit : chronologicalCommits) {
                staticMetricsCalculator.calculateAndSaveMetrics(commit);
            }
            dbManager.commit();
            long p2Ms = System.currentTimeMillis() - p2Start;
            System.out.printf("PHASE2_FEATURE_EXTRACTION_MS=%d%n", p2Ms);

            // ---- Phase 3: training (reported configuration only) ----
            long p3Start = System.currentTimeMillis();
            DataPreprocessor dataPreprocessor = new DataPreprocessor(dbManager, gitManager);
            Instances allData = dataPreprocessor.loadDataFromDatabase();
            System.out.println("INSTANCES=" + allData.numInstances() + " RAW_ATTRIBUTES=" + allData.numAttributes());

            Instances data14 = keepOnlyCanonicalFeatures(allData);
            System.out.println("ATTRIBUTES_AFTER_FILTER=" + data14.numAttributes() + " (expect 15: 14 features + class)");

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

            Evaluation eval = new Evaluation(data14);
            eval.crossValidateModel(costSensitive, data14, 10, new Random(42));
            long p3Ms = System.currentTimeMillis() - p3Start;
            System.out.printf("PHASE3_TRAINING_MS=%d%n", p3Ms);

            int positiveClassIndex = 1;
            if (data14.classAttribute().value(0).toLowerCase().contains("true")
                    || data14.classAttribute().value(0).toLowerCase().contains("buggy")) {
                positiveClassIndex = 0;
            }
            System.out.printf(java.util.Locale.US, "RESULT precision=%.4f recall=%.4f f1=%.4f mcc=%.4f%n",
                    eval.precision(positiveClassIndex), eval.recall(positiveClassIndex),
                    eval.fMeasure(positiveClassIndex), eval.matthewsCorrelationCoefficient(positiveClassIndex));

            long totalMs = p1Ms + p2Ms + p3Ms;
            System.out.printf("TOTAL_MS=%d TOTAL_MIN=%.2f%n", totalMs, totalMs / 60000.0);

        } finally {
            if (repository != null && gitManager != null) {
                gitManager.closeRepository(repository);
            }
            if (dbManager != null) {
                dbManager.close();
            }
        }
    }

    private static Instances keepOnlyCanonicalFeatures(Instances data) throws Exception {
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
                indicesToRemove.append(i + 1);
            }
        }

        if (indicesToRemove.length() == 0) {
            return data;
        }
        Remove removeFilter = new Remove();
        removeFilter.setAttributeIndices(indicesToRemove.toString());
        removeFilter.setInputFormat(data);
        return Filter.useFilter(data, removeFilter);
    }
}
