package org.tymz.feature;

import weka.core.Instances;
import java.util.List;

/**
 * Container class to hold training and prediction datasets with revision ID tracking.
 * This is used for predictions where we need to map results back to file paths.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 */
public class PredictionData {
    public final Instances trainingSet;
    public final Instances predictionSet;
    public final List<Long> predictionRevisionIds;

    public PredictionData(Instances trainingSet, Instances predictionSet, List<Long> predictionRevisionIds) {
        this.trainingSet = trainingSet;
        this.predictionSet = predictionSet;
        this.predictionRevisionIds = predictionRevisionIds;
    }
    
    @Override
    public String toString() {
        return String.format("PredictionData{training: %d instances, prediction: %d instances, revisionIds: %d}", 
            trainingSet.numInstances(), predictionSet.numInstances(), predictionRevisionIds.size());
    }
}
