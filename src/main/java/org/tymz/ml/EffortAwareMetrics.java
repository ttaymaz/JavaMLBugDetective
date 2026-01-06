package org.tymz.ml;

import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.Instances;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Effort-aware metrics calculator for bug prediction evaluation.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class EffortAwareMetrics {
    private static class PredictedFile {
        final double probability;
        final boolean isActuallyBuggy;
        final double effort;

        PredictedFile(double probability, boolean isActuallyBuggy, double effort) {
            this.probability = probability;
            this.isActuallyBuggy = isActuallyBuggy;
            this.effort = effort;
        }
    }

    public static Map<String, Double> calculate(Classifier classifier, Instances testSet) throws Exception {
        Map<String, Double> results = new HashMap<>();
        if (testSet.numInstances() == 0) {
            results.put("Recall@20%Effort", 0.0);
            results.put("Popt", 0.0);
            return results;
        }
        
        Attribute ncssAttribute = testSet.attribute("NCSS_CLASS");
        if (ncssAttribute == null) {
            throw new IllegalArgumentException("Dataset must contain 'NCSS_CLASS' attribute for effort calculation.");
        }
        
        List<PredictedFile> predictions = new ArrayList<>();
        double totalEffort = 0;
        int totalActualBugs = 0;
        int buggyClassIndex = testSet.classAttribute().value(0).equals("buggy") ? 0 : 1;

        for (int i = 0; i < testSet.numInstances(); i++) {
            weka.core.Instance instance = testSet.instance(i);
            double[] distribution = classifier.distributionForInstance(instance);
            double buggyProbability = distribution[buggyClassIndex];
            boolean actual = (instance.classValue() == buggyClassIndex);
            double effort = instance.value(ncssAttribute);
            predictions.add(new PredictedFile(buggyProbability, actual, effort));
            totalEffort += effort;
            if (actual) totalActualBugs++;
        }

        if (totalActualBugs == 0 || totalEffort == 0) {
            results.put("Recall@20%Effort", 0.0);
            results.put("Popt", 0.0);
            return results;
        }

        predictions.sort(Comparator.comparingDouble(p -> -p.probability));
        double cumulativeEffort = 0, cumulativeBugsFound = 0, recallAt20Effort = 0.0, maxPopt = 0.0;
        boolean found20Effort = false;

        for (PredictedFile file : predictions) {
            cumulativeEffort += file.effort;
            if (file.isActuallyBuggy) cumulativeBugsFound++;
            double effortRatio = cumulativeEffort / totalEffort;
            double recallRatio = cumulativeBugsFound / totalActualBugs;
            if (recallRatio - effortRatio > maxPopt) maxPopt = recallRatio - effortRatio;
            if (!found20Effort && effortRatio >= 0.20) {
                recallAt20Effort = recallRatio;
                found20Effort = true;
            }
        }
        results.put("Recall@20%Effort", recallAt20Effort);
        results.put("Popt", maxPopt);
        return results;
    }
}
