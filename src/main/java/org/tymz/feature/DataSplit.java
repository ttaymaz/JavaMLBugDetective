package org.tymz.feature;

import weka.core.Instances;

/**
 * Container class to hold training and test datasets.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class DataSplit {
    private final Instances trainingSet;
    private final Instances testSet;
    
    public DataSplit(Instances trainingSet, Instances testSet) {
        this.trainingSet = trainingSet;
        this.testSet = testSet;
    }
    
    public Instances getTrainingSet() { return trainingSet; }
    public Instances getTestSet() { return testSet; }
    
    @Override
    public String toString() {
        return String.format("DataSplit{training: %d instances, test: %d instances}", 
            trainingSet.numInstances(), testSet.numInstances());
    }
}
