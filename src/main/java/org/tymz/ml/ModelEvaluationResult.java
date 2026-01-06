package org.tymz.ml;

import weka.classifiers.Evaluation;
import java.util.Map;

/**
 * A Data Transfer Object (DTO) to hold all evaluation results for a single model run.
 * This bundles Weka's standard evaluation with our custom effort-aware metrics.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class ModelEvaluationResult {
    private final Evaluation evaluation;
    private final Map<String, Double> effortAwareMetrics;

    public ModelEvaluationResult(Evaluation evaluation, Map<String, Double> effortAwareMetrics) {
        this.evaluation = evaluation;
        this.effortAwareMetrics = effortAwareMetrics;
    }

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public Map<String, Double> getEffortAwareMetrics() {
        return effortAwareMetrics;
    }
}
