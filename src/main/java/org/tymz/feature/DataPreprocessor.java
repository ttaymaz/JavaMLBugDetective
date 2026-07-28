package org.tymz.feature;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.tymz.db.DatabaseManager;
import org.tymz.git.GitRepositoryManager;
import org.tymz.config.Config;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ArffSaver;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * DataPreprocessor converts raw database data into WEKA-compatible format for machine learning.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * This class is responsible for:
 * 1. Loading all collected metrics and bug labels from the database
 * 2. Creating WEKA Instances object with proper attributes
 * 3. Populating the dataset with metric values and class labels
 * 4. Splitting data into training and test sets based on commit chronology
 * 
 * The class handles both process metrics (NR, NDEV, AGE, EXP) and static metrics 
 * (WEIGHED_METHOD_COUNT, NCSS, etc.) along with bug labels for supervised learning.
 */
public class DataPreprocessor {
    
    private final DatabaseManager databaseManager;
    private final GitRepositoryManager gitManager;
    
    // Mapping to track which Instance belongs to which FileRevisionData
    private final Map<Instance, FileRevisionData> instanceToRevisionMap = new HashMap<>();
    
    // Standard metric names that we expect to find in the database
    private static final String[] PROCESS_METRICS = {"NR", "NDEV", "AGE", "EXP", "LINES_ADDED", "LINES_DELETED", "HUNK_COUNT"};
    private static final String[] STATIC_METRICS = {
        "WMC", "TCC", "RFC", "LCOM", "CBO", "NCSS_CLASS", "CYCLO_SUM"
    };
    

    

    
    /**
     * Internal class to represent a file revision with all its metrics and label
     */
    public static class FileRevisionData {     
        final long fileRevisionId;   
        final LocalDateTime commitDate;
        final Map<String, Double> metrics;
        final boolean isBuggy;
        
        FileRevisionData(long fileRevisionId, LocalDateTime commitDate, boolean isBuggy) {        
            this.fileRevisionId = fileRevisionId;
            this.commitDate = commitDate;
            this.metrics = new HashMap<>();
            this.isBuggy = isBuggy;
        }
    }
    
    /**
     * Container class to hold training and prediction datasets with revision ID tracking
     * This is used for predictions where we need to map results back to file paths
     */
    public static class PredictionData {
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
    
    /**
     * Container class for version-based data splits
     * Used for version-based validation where training and test sets
     * are determined by actual software releases rather than arbitrary time splits
     */
    public static class DataSplit {
        private final Instances trainingSet;
        private final Instances testSet;
        private final String splitDescription;

        public DataSplit(Instances trainingSet, Instances testSet, String splitDescription) {
            this.trainingSet = trainingSet;
            this.testSet = testSet;
            this.splitDescription = splitDescription;
        }

        public Instances getTrainingSet() { return trainingSet; }
        public Instances getTestSet() { return testSet; }
        public String getSplitDescription() { return splitDescription; }
        
        @Override
        public String toString() {
            return String.format("DataSplit{training: %d, test: %d, %s}", 
                trainingSet.numInstances(), testSet.numInstances(), splitDescription);
        }
    }
    
    /**
     * Constructor with dependency injection.
     * 
     * @param databaseManager Injected database manager instance
     */
    public DataPreprocessor(DatabaseManager databaseManager, GitRepositoryManager gitManager) {
        this.databaseManager = databaseManager; // Use injected instance
        this.gitManager = gitManager;
    }
    
        /**
     * Loads data from database and converts to WEKA Instances format
     * 
     * Process:
     * 1. Queries the database to get all file revisions with their metrics and bug labels
     * 2. Creates the WEKA attribute structure (features + class label)
     * 3. Populates instances with metric values and bug labels
     * 
     * @return WEKA Instances object containing all the preprocessed data
     * @throws SQLException if database operations fail
     * @throws IOException if ARFF file saving fails
     */
    public Instances loadDataFromDatabase() throws SQLException, IOException {
        System.out.println("Starting data preprocessing from database...");
        
        // Step 1: Load file revisions with bug labels
        Map<Long, FileRevisionData> fileRevisions = loadFileRevisions();
        System.out.println("✓ Loaded " + fileRevisions.size() + " file revisions");
        
        // Step 2: Load all metrics for these file revisions
        loadMetrics(fileRevisions);
        System.out.println("✓ Loaded metrics for file revisions");
        
        // Step 3: Use all revisions without filtering
        List<FileRevisionData> allRevisions = new ArrayList<>(fileRevisions.values());

        // Step 4: Create WEKA dataset structure
        Instances dataset = createWekaDataset();
        
        // Step 5: Populate the dataset with actual data
        populateDataset(dataset, allRevisions); // Filtrelenmemiş listeyi buraya ver.
        System.out.println("✓ Dataset created with " + dataset.numInstances() + " instances");
        
        // Step 6: Save dataset to ARFF file
        saveDatasetToArff(dataset);
        
        return dataset;
    }
    
    /**
     * Loads file revisions from database with their bug labels
     */
    private Map<Long, FileRevisionData> loadFileRevisions() throws SQLException {
        Map<Long, FileRevisionData> fileRevisions = new HashMap<>();
        
        String fileExtensions = Config.getFileExtensions();
        String sql = """
            SELECT 
                fr.id,
                c.commit_date,
                COALESCE(bl.is_buggy, 0) as is_buggy
            FROM file_revisions fr
            INNER JOIN commits c ON fr.commit_hash = c.commit_hash
            LEFT JOIN bug_labels bl ON fr.id = bl.file_revision_id
            WHERE fr.file_path LIKE '%' || ? || '%'
            ORDER BY c.commit_date, fr.id
            """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, fileExtensions);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                long id = rs.getLong("id");
                String commitDateStr = rs.getString("commit_date");
                
                LocalDateTime commitDate = null;
                if (commitDateStr != null) {
                    try {
                        // Parse ISO datetime format
                        commitDate = LocalDateTime.parse(commitDateStr.replace(" ", "T"));
                    } catch (Exception e) {
                        System.out.println("WARNING: Failed to parse commit date: " + commitDateStr);
                    }
                }
                
                boolean isBuggy = rs.getBoolean("is_buggy");
                
                fileRevisions.put(id, new FileRevisionData(id, commitDate, isBuggy));
            }
            rs.close();
        }
        
        return fileRevisions;
    }
    
    /**
     * Loads all metrics for the given file revisions
     */
    private void loadMetrics(Map<Long, FileRevisionData> fileRevisions) throws SQLException {
        if (fileRevisions.isEmpty()) {
            return;
        }
        
        // Build IN clause for file revision IDs
        String inClause = fileRevisions.keySet().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        
        String sql = """
            SELECT 
                file_revision_id,
                metric_name,
                metric_value,
                metric_type
            FROM metrics 
            WHERE file_revision_id IN (""" + inClause + """
            )
            ORDER BY file_revision_id, metric_name
            """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                long fileRevisionId = rs.getLong("file_revision_id");
                String metricName = rs.getString("metric_name");
                double metricValue = rs.getDouble("metric_value");
                
                FileRevisionData revision = fileRevisions.get(fileRevisionId);
                if (revision != null) {
                    revision.metrics.put(metricName, metricValue);
                }
            }
        }
    }
    
    /**
     * Creates the WEKA dataset structure with all attributes
     */
    private Instances createWekaDataset() {
        ArrayList<Attribute> attributes = new ArrayList<>();
        
        // Remove file_revision_id from features to prevent target leakage
        // attributes.add(new Attribute("file_revision_id")); // REMOVED
        
        // Add process metrics attributes
        for (String metric : PROCESS_METRICS) {
            attributes.add(new Attribute(metric));
        }
        
        // Add static metrics attributes  
        for (String metric : STATIC_METRICS) {
            attributes.add(new Attribute(metric));
        }
        
        // Add new derived ratio metrics
        attributes.add(new Attribute("CHANGE_DELTA")); // (LINES_ADDED - LINES_DELETED)
        attributes.add(new Attribute("RELATIVE_CHURN")); // (LINES_ADDED + LINES_DELETED) / NCSS_CLASS
        attributes.add(new Attribute("DELETION_RATIO")); // LINES_DELETED / (LINES_ADDED + LINES_DELETED)
        
        // Add class attribute (nominal: clean or buggy)
        ArrayList<String> classValues = new ArrayList<>();
        classValues.add("clean");
        classValues.add("buggy");
        attributes.add(new Attribute("is_buggy", classValues));
        
        // Create empty dataset
        Instances dataset = new Instances("BugPredictionDataset", attributes, 0);
        
        // Set class index (last attribute)
        dataset.setClassIndex(dataset.numAttributes() - 1);
        
        return dataset;
    }
    
    /**
     * Populates the WEKA dataset with actual data from file revisions
     */
    private void populateDataset(Instances dataset, List<FileRevisionData> revisions) {
        // Clear previous mappings
        instanceToRevisionMap.clear();
        
        for (FileRevisionData revision : revisions) {
            // Create a new instance with the correct number of attributes
            Instance instance = new DenseInstance(dataset.numAttributes());
            instance.setDataset(dataset);
            
            int attrIndex = 0;
            
            // Remove file_revision_id from features to prevent target leakage
            // instance.setValue(attrIndex++, revision.fileRevisionId); // REMOVED
            
            // Set process metrics values
            for (String metric : PROCESS_METRICS) {
                double value = revision.metrics.getOrDefault(metric, 0.0);
                instance.setValue(attrIndex++, value);
            }
            
            // Set static metrics values (use default values if not available in database)
            for (String metric : STATIC_METRICS) {
                double value = revision.metrics.getOrDefault(metric, getDefaultStaticMetricValue(metric));
                instance.setValue(attrIndex++, value);
            }
            
            // Calculate and set derived ratio metrics
            double linesAdded = revision.metrics.getOrDefault("LINES_ADDED", 0.0);
            double linesDeleted = revision.metrics.getOrDefault("LINES_DELETED", 0.0);
            double ncss = revision.metrics.getOrDefault("NCSS_CLASS", 1.0); // Default to 1 to avoid division by zero

            // 1. Change Delta
            instance.setValue(attrIndex++, linesAdded - linesDeleted);

            // 2. Relative Churn
            double totalChurn = linesAdded + linesDeleted;
            double relativeChurn = (ncss > 0) ? totalChurn / ncss : 0.0;
            instance.setValue(attrIndex++, relativeChurn);

            // 3. Deletion Ratio
            double deletionRatio = (totalChurn > 0) ? linesDeleted / totalChurn : 0.0;
            instance.setValue(attrIndex++, deletionRatio);
            
            // Set class label (buggy or clean)
            String classLabel = revision.isBuggy ? "buggy" : "clean";
            instance.setValue(attrIndex, classLabel);
            
            // Add instance to dataset
            dataset.add(instance);
            
            // Store mapping for temporal split using revision ID
            instanceToRevisionMap.put(instance, revision);
        }
    }
    
    /**
     * Splits the dataset using temporal logic: 
     * - Test set contains only instances from the latest commit
     * - Training set contains all other instances
     * @param allData Complete dataset to split
     * @return DataSplit object containing training and test sets
     * @throws SQLException if database operations fail
     */
    public DataSplit splitData(Instances allData) throws SQLException {
        if (allData.numInstances() == 0) {
            // Return empty splits for empty dataset
            Instances emptyInstances = new Instances(allData, 0);
            return new DataSplit(emptyInstances, emptyInstances, "Empty dataset");
        }
        
        // Find the latest commit date from all instances
        LocalDateTime latestCommitDate = null;
        for (int i = 0; i < allData.numInstances(); i++) {
            Instance instance = allData.instance(i);
            FileRevisionData revision = instanceToRevisionMap.get(instance);
            
            if (revision != null && revision.commitDate != null) {
                if (latestCommitDate == null || revision.commitDate.isAfter(latestCommitDate)) {
                    latestCommitDate = revision.commitDate;
                }
            }
        }
        
        if (latestCommitDate == null) {
            // Fallback to original percentage-based split if no commit dates available
            System.out.println("WARNING: No commit dates found, falling back to percentage-based split");
            double trainRatio = Config.getMLTrainRatio();
            int splitIndex = (int) (allData.numInstances() * trainRatio);
            Instances trainSet = new Instances(allData, 0, splitIndex);
            Instances testSet = new Instances(allData, splitIndex, allData.numInstances() - splitIndex);
            return new DataSplit(trainSet, testSet, "Percentage-based fallback split");
        }
        
        // Create separate datasets for training and testing
        Instances trainingSet = new Instances(allData, 0);
        Instances testSet = new Instances(allData, 0);
        
        // Split instances based on commit date
        for (int i = 0; i < allData.numInstances(); i++) {
            Instance instance = allData.instance(i);
            FileRevisionData revision = instanceToRevisionMap.get(instance);
            
            if (revision != null && revision.commitDate != null) {
                if (revision.commitDate.equals(latestCommitDate)) {
                    // Instance is from the latest commit - add to test set
                    testSet.add((Instance) instance.copy());
                } else {
                    // Instance is from earlier commits - add to training set
                    trainingSet.add((Instance) instance.copy());
                }
            } else {
                // If no commit date, add to training set as safe default
                trainingSet.add((Instance) instance.copy());
            }
        }
        
        System.out.println("Temporal split completed:");
        System.out.println("  Latest commit date: " + latestCommitDate);
        System.out.println("  Training set size: " + trainingSet.numInstances() + " instances");
        System.out.println("  Test set size: " + testSet.numInstances() + " instances");
        
        String description = String.format("Temporal split with latest commit (%s)", 
            latestCommitDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return new DataSplit(trainingSet, testSet, description);
    }
    
    /**
     * Splits the dataset by latest commit for prediction purposes.
     * - Training set: All instances from commits BEFORE the latest commit
     * - Prediction set: Only instances from the LATEST commit
     * 
     * @param allData Complete dataset to split
     * @return PredictionData with trainingSet (historical data), predictionSet (latest commit data), and revision IDs
     * @throws SQLException if database operations fail
     */
    public PredictionData splitByLatestCommit(Instances allData) throws SQLException {
        if (allData.numInstances() == 0) {
            Instances emptyInstances = new Instances(allData, 0);
            return new PredictionData(emptyInstances, emptyInstances, new ArrayList<>());
        }
        
        // Create a map of file revision ID -> commit date
        Map<Long, LocalDateTime> revisionDateMap = new HashMap<>();
        try (Connection conn = databaseManager.getConnection()) {
            String sql = """
                SELECT 
                    fr.id,
                    c.commit_date
                FROM file_revisions fr
                INNER JOIN commits c ON fr.commit_hash = c.commit_hash
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    long revisionId = rs.getLong("id");
                    String commitDateStr = rs.getString("commit_date");
                    
                    if (commitDateStr != null) {
                        try {
                            LocalDateTime commitDate = LocalDateTime.parse(commitDateStr.replace(" ", "T"));
                            revisionDateMap.put(revisionId, commitDate);
                        } catch (Exception e) {
                            System.out.println("WARNING: Failed to parse commit date: " + commitDateStr);
                        }
                    }
                }
            }
        }
        
        // Find the latest commit date from all instances
        LocalDateTime latestCommitDate = null;
        for (int i = 0; i < allData.numInstances(); i++) {
            Instance instance = allData.instance(i);
            FileRevisionData revision = instanceToRevisionMap.get(instance);
            
            if (revision != null) {
                LocalDateTime commitDate = revisionDateMap.get(revision.fileRevisionId);
                if (commitDate != null) {
                    if (latestCommitDate == null || commitDate.isAfter(latestCommitDate)) {
                        latestCommitDate = commitDate;
                    }
                }
            }
        }
        
        if (latestCommitDate == null) {
            System.out.println("WARNING: No commit dates found for latest commit split");
            Instances emptyInstances = new Instances(allData, 0);
            return new PredictionData(allData, emptyInstances, new ArrayList<>());
        }
        
        // Create separate datasets
        Instances trainingSet = new Instances(allData, 0);  // Historical commits
        Instances predictionSet = new Instances(allData, 0); // Latest commit only
        List<Long> predictionRevisionIds = new ArrayList<>(); // Track revision IDs for predictions
        
        // Split instances based on commit date
        for (int i = 0; i < allData.numInstances(); i++) {
            Instance instance = allData.instance(i);
            FileRevisionData revision = instanceToRevisionMap.get(instance);
            
            if (revision != null) {
                LocalDateTime commitDate = revisionDateMap.get(revision.fileRevisionId);
                if (commitDate != null && commitDate.equals(latestCommitDate)) {
                    // Instance is from the latest commit - add to prediction set
                    predictionSet.add((Instance) instance.copy());
                    predictionRevisionIds.add(revision.fileRevisionId);
                } else {
                    // Instance is from earlier commits - add to training set
                    trainingSet.add((Instance) instance.copy());
                }
            } else {
                // If no revision mapping, add to training set as safe default
                trainingSet.add((Instance) instance.copy());
            }
        }
        
        System.out.println("Latest commit split completed:");
        System.out.println("  Latest commit date: " + latestCommitDate);
        System.out.println("  Training set (historical): " + trainingSet.numInstances() + " instances");
        System.out.println("  Prediction set (latest commit): " + predictionSet.numInstances() + " instances");
        System.out.println("  Prediction revision IDs tracked: " + predictionRevisionIds.size());
        
        return new PredictionData(trainingSet, predictionSet, predictionRevisionIds);
    }
    
    /**
     * Gets statistics about the loaded data
     * 
     * @param data The dataset to analyze
     * @return String containing dataset statistics
     */
    public String getDatasetStatistics(Instances data) {
        if (data.numInstances() == 0) {
            return "Dataset is empty";
        }
        
        StringBuilder stats = new StringBuilder();
        stats.append("Dataset Statistics:\n");
        stats.append("- Total instances: ").append(data.numInstances()).append("\n");
        stats.append("- Total attributes: ").append(data.numAttributes()).append("\n");
        stats.append("- Class attribute: ").append(data.classAttribute().name()).append("\n");
        
        // Class distribution
        int[] classCounts = new int[data.numClasses()];
        for (int i = 0; i < data.numInstances(); i++) {
            classCounts[(int) data.instance(i).classValue()]++;
        }
        
        stats.append("- Class distribution:\n");
        for (int i = 0; i < data.numClasses(); i++) {
            String className = data.classAttribute().value(i);
            double percentage = (classCounts[i] * 100.0) / data.numInstances();
            stats.append(String.format("  - %s: %d (%.1f%%)%n", className, classCounts[i], percentage));
        }
        
        return stats.toString();
    }
    
    /**
     * Saves the dataset to an ARFF file for reuse and external analysis
     * 
     * @param dataset The WEKA Instances object to save
     * @throws IOException if file writing fails
     */
    private void saveDatasetToArff(Instances dataset) throws IOException {
        // Get project name from repository URL
        String projectName = extractProjectNameFromConfig();
        String filename = projectName + "-dataset.arff";
        
        // Create ArffSaver and save the dataset
        ArffSaver saver = new ArffSaver();
        saver.setInstances(dataset);
        saver.setFile(new File(filename));
        saver.writeBatch();
        
        System.out.println("✓ Dataset successfully saved to " + filename);
    }
    
    /**
     * Extracts project name from repository configuration
     * 
     * @return project name for filename generation
     */
    private String extractProjectNameFromConfig() {
        String repositoryUrl = Config.getRepositoryUrl();
        if (repositoryUrl != null && !repositoryUrl.trim().isEmpty()) {
            // Extract project name from URL (e.g., "https://github.com/google/gson" -> "gson")
            String[] parts = repositoryUrl.split("/");
            if (parts.length > 0) {
                return parts[parts.length - 1].replaceAll("\\.git$", ""); // Remove .git extension if present
            }
        }
        
        // Fallback to a generic name
        return "dataset";
    }
    
    /**
     * Provides reasonable default values for static metrics when they are not available
     * in the database (e.g., due to PMD analysis failures).
     * @param metricName Name of the static metric
     * @return Default value for the metric
     */
    private double getDefaultStaticMetricValue(String metricName) {
        return switch (metricName) {
            case "WMC" -> 5.0;          // Weighted Methods per Class
            case "TCC" -> 0.6;          // Tight Class Cohesion
            case "RFC" -> 8.0;          // Response for Class
            case "LCOM" -> 2.0;         // Lack of Cohesion in Methods
            case "CBO" -> 3.0;          // Coupling Between Objects
            case "NCSS_CLASS" -> 25.0;  // Non-Commenting Source Statements (Class)
            case "CYCLO_SUM" -> 10.0;   // Total Cyclomatic Complexity
            default -> 1.0;             // Generic default
        };
    }
    
    /**
     * Splits the dataset based on version boundaries for version-based validation.
     * 
     * This method implements a version-based validation approach where the model is trained
     * on all data up to and including a specific version, and tested on data from subsequent
     * versions. This approach mirrors real-world deployment scenarios where models predict
     * bugs in future releases based on historical patterns.
     * 
     * @param allData Complete dataset to split
     * @param trainEndCommitHash Commit hash of the last commit to include in training set
     * @param commitToRevisionMap Mapping from commit hash to file revision data for temporal tracking
     * @param splitDescription Human-readable description of the split for reporting
     * @return DataSplit with version-based training and test sets
     * @throws SQLException if database operations fail
     */
    public DataSplit splitByVersion(Instances allData, String trainEndCommitHash, 
                                   Map<String, List<FileRevisionData>> commitToRevisionMap, 
                                   String splitDescription) throws SQLException {
        
        if (allData.numInstances() == 0) {
            Instances emptyInstances = new Instances(allData, 0);
            return new DataSplit(emptyInstances, emptyInstances, "Empty dataset - " + splitDescription);
        }
        
        System.out.println("Performing version-based data split: " + splitDescription);
        System.out.println("Training set cutoff commit: " + trainEndCommitHash.substring(0, Math.min(8, trainEndCommitHash.length())));
        
        // Get the cutoff date from the specified commit
        LocalDateTime cutoffDate = null;
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "SELECT commit_date FROM commits WHERE commit_hash = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, trainEndCommitHash);
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    String commitDateStr = rs.getString("commit_date");
                    if (commitDateStr != null) {
                        try {
                            cutoffDate = LocalDateTime.parse(commitDateStr.replace(" ", "T"));
                        } catch (Exception e) {
                            System.out.println("WARNING: Failed to parse cutoff commit date: " + commitDateStr);
                        }
                    }
                }
            }
        }
        
        if (cutoffDate == null) {
            System.out.println("WARNING: Could not determine cutoff date, falling back to commit hash comparison");
        }
        
        // Create separate datasets based on version boundary
        Instances trainingSet = new Instances(allData, 0);  // Historical versions
        Instances testSet = new Instances(allData, 0);      // Target version
        
        // Split instances based on version boundary
        int trainingCount = 0, testCount = 0;
        
        for (int i = 0; i < allData.numInstances(); i++) {
            Instance instance = allData.instance(i);
            FileRevisionData revision = instanceToRevisionMap.get(instance);
            
            if (revision != null) {
                boolean belongsToTraining = false;
                
                if (cutoffDate != null && revision.commitDate != null) {
                    // Use date-based comparison (more reliable)
                    belongsToTraining = revision.commitDate.isBefore(cutoffDate) || 
                                      revision.commitDate.isEqual(cutoffDate);
                } else {
                    // Fallback to commit hash comparison
                    // Check if this revision's commit is in the training set
                    List<FileRevisionData> commitRevisions = commitToRevisionMap.getOrDefault(trainEndCommitHash, new ArrayList<>());
                    belongsToTraining = commitRevisions.stream()
                        .anyMatch(r -> r.fileRevisionId == revision.fileRevisionId);
                    
                    // If not found in the exact commit, assume it's older (training)
                    if (!belongsToTraining) {
                        belongsToTraining = true; // Conservative assumption for unknown commits
                    }
                }
                
                if (belongsToTraining) {
                    trainingSet.add((Instance) instance.copy());
                    trainingCount++;
                } else {
                    testSet.add((Instance) instance.copy());
                    testCount++;
                }
            } else {
                // If no revision mapping, add to training set as safe default
                trainingSet.add((Instance) instance.copy());
                trainingCount++;
            }
        }
        
        System.out.println("Version-based split completed:");
        System.out.println("  Training set (historical versions): " + trainingCount + " instances");
        System.out.println("  Test set (target version): " + testCount + " instances");
        
        if (testCount == 0) {
            System.out.println("  WARNING: No test instances found. Check version selection.");
        }
        
        if (cutoffDate != null) {
            System.out.println("  Cutoff date: " + cutoffDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        
        return new DataSplit(trainingSet, testSet, splitDescription);
    }
    
    /**
     * Gets the FileRevisionData for a given instance
     * @param instance The instance to get revision data for
     * @return FileRevisionData or null if not found
     */
    public FileRevisionData getRevisionForInstance(Instance instance) {
        return instanceToRevisionMap.get(instance);
    }
    
    /**
     * Builds a mapping from commit hash to list of file revisions for version-based splitting.
     * 
     * @return Map from commit hash to list of FileRevisionData
     * @throws SQLException if database access fails
     */
    public Map<String, List<FileRevisionData>> buildCommitToRevisionMap() throws SQLException {
        Map<String, List<FileRevisionData>> commitToRevisionMap = new HashMap<>();
        
        System.out.println("Building commit-to-revision mapping for version-based splitting...");
        
        try (Connection conn = databaseManager.getConnection()) {
            String sql = """
                SELECT fr.id, fr.file_path, bl.is_buggy, c.commit_hash, c.commit_date
                FROM file_revisions fr
                JOIN commits c ON fr.commit_hash = c.commit_hash
                LEFT JOIN bug_labels bl ON fr.id = bl.file_revision_id
                ORDER BY c.commit_date ASC
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                
                while (rs.next()) {
                    long revisionId = rs.getLong("id");
                    boolean isBuggy = rs.getBoolean("is_buggy");
                    String commitHash = rs.getString("commit_hash");
                    
                    // Convert timestamp to LocalDateTime with error handling
                    LocalDateTime commitDate;
                    try {
                        java.sql.Timestamp timestamp = rs.getTimestamp("commit_date");
                        commitDate = timestamp.toLocalDateTime();
                    } catch (SQLException e) {
                        // Fallback: try parsing as string
                        String dateStr = rs.getString("commit_date");
                        commitDate = LocalDateTime.parse(dateStr.replace(" ", "T"));
                    }
                    
                    FileRevisionData revisionData = new FileRevisionData(revisionId, commitDate, isBuggy);
                    
                    commitToRevisionMap.computeIfAbsent(commitHash, k -> new ArrayList<>())
                                     .add(revisionData);
                }
            }
        }
        
        System.out.println("✓ Commit-to-revision mapping built with " + commitToRevisionMap.size() + " commits");
        
        return commitToRevisionMap;
    }
    
    /**
     * Splits data for latest version prediction with SEPARATE datasets:
     * 1. Training Dataset: Historical file revisions WITH bug labels (from database)
     * 2. Prediction Dataset: Latest version files WITHOUT bug labels (to be predicted)
     * 
     * This approach ensures we don't leak future information into training and
     * actually predict unknown bug labels for the latest version.
     */
    public PredictionData splitByLatestVersion(Instances allData, String latestVersionCommit) throws SQLException {
        System.out.println("🔄 Creating separate training and prediction datasets...");
        
        // Step 1: Create training dataset from ALL historical data (excluding latest version)
        Instances trainingSet = createTrainingDataset(latestVersionCommit);
        
        // Step 2: Create prediction dataset from latest version files (without bug labels)  
        Instances predictionSet = createPredictionDataset(latestVersionCommit);
        List<Long> predictionRevisionIds = getPredictionRevisionIds(latestVersionCommit);
        
        // Step 3: Save separate ARFF files for training and prediction datasets
        try {
            saveTrainingAndPredictionArff(trainingSet, predictionSet);
        } catch (IOException e) {
            System.err.println("Warning: Could not save ARFF files: " + e.getMessage());
        }
        
        System.out.println("✓ Latest version prediction split:");
        System.out.println("   Training set: " + trainingSet.numInstances() + " instances");
        System.out.println("   Prediction set: " + predictionSet.numInstances() + " instances");
        
        return new PredictionData(trainingSet, predictionSet, predictionRevisionIds);
    }
    
    /**
     * Creates training dataset from historical data (before latest version) WITH bug labels
     */
    private Instances createTrainingDataset(String latestVersionCommit) throws SQLException {
        // Get cutoff date for latest version
        String cutoffDate = getCommitDate(latestVersionCommit);
        if (cutoffDate == null) {
            throw new SQLException("Could not find commit date for: " + latestVersionCommit);
        }
        
        // Load historical file revisions WITH bug labels
        Map<Long, FileRevisionData> historicalRevisions = loadHistoricalFileRevisions(cutoffDate);
        loadMetrics(historicalRevisions);
        
        // Create WEKA dataset and populate with historical data
        Instances trainingSet = createWekaDataset();
        populateDataset(trainingSet, new ArrayList<>(historicalRevisions.values()));
        
        System.out.println("✓ Training dataset created from historical data (" + historicalRevisions.size() + " revisions)");
        return trainingSet;
    }
    
    /**
     * Creates prediction dataset from latest version files WITHOUT bug labels
     */
    private Instances createPredictionDataset(String latestVersionCommit) throws SQLException {
        // Get latest version date
        String latestVersionDate = getCommitDate(latestVersionCommit);
        if (latestVersionDate == null) {
            throw new SQLException("Could not find commit date for: " + latestVersionCommit);
        }
        
        // Load latest version file revisions WITHOUT bug labels
        Map<Long, FileRevisionData> latestRevisions = loadLatestVersionFileRevisions(latestVersionDate);
        loadMetrics(latestRevisions);
        
        // Create WEKA dataset structure (same as training but will have missing class values)
        Instances predictionSet = createWekaDataset();
        
        // Populate with latest version data (no bug labels - class will be missing)
        populatePredictionDataset(predictionSet, new ArrayList<>(latestRevisions.values()));
        
        System.out.println("✓ Prediction dataset created from latest version (" + latestRevisions.size() + " revisions)");
        return predictionSet;
    }
    
    /**
     * Gets commit date for a given commit hash
     * Falls back to Git repository if not found in database
     */
    private String getCommitDate(String commitHash) throws SQLException {
        try (Connection conn = databaseManager.getConnection()) {
            String sql = "SELECT commit_date FROM commits WHERE commit_hash = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, commitHash);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("commit_date");
                    }
                }
            }
        }
        
        // Fallback: Query Git repository directly for commit date
        try {
            Repository repository = gitManager.loadOrCloneRepository();
            try (Git git = new Git(repository);
                 RevWalk revWalk = new RevWalk(repository)) {
                ObjectId commitId = repository.resolve(commitHash);
                if (commitId != null) {
                    RevCommit commit = revWalk.parseCommit(commitId);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    return sdf.format(new Date(commit.getCommitTime() * 1000L));
                }
            }
        } catch (Exception e) {
            System.out.println("Warning: Could not retrieve commit date from Git for " + commitHash + ": " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Loads historical file revisions (before cutoff date) WITH bug labels
     */
    private Map<Long, FileRevisionData> loadHistoricalFileRevisions(String cutoffDate) throws SQLException {
        Map<Long, FileRevisionData> fileRevisions = new HashMap<>();
        String fileExtensions = Config.getFileExtensions();
        
        String sql = """
            SELECT 
                fr.id,
                c.commit_date,
                COALESCE(bl.is_buggy, 0) as is_buggy
            FROM file_revisions fr
            INNER JOIN commits c ON fr.commit_hash = c.commit_hash
            LEFT JOIN bug_labels bl ON fr.id = bl.file_revision_id
            WHERE fr.file_path LIKE '%' || ? || '%'
            AND c.commit_date < ?
            ORDER BY c.commit_date, fr.id
            """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, fileExtensions);
            stmt.setString(2, cutoffDate);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    LocalDateTime commitDate = parseCommitDate(rs.getString("commit_date"));
                    boolean isBuggy = rs.getBoolean("is_buggy");
                    
                    fileRevisions.put(id, new FileRevisionData(id, commitDate, isBuggy));
                }
            }
        }
        
        return fileRevisions;
    }
    
    /**
     * Loads latest version file revisions WITHOUT bug labels (for prediction)
     */
    private Map<Long, FileRevisionData> loadLatestVersionFileRevisions(String latestVersionDate) throws SQLException {
        Map<Long, FileRevisionData> fileRevisions = new HashMap<>();
        String fileExtensions = Config.getFileExtensions();
        
        // Find latest revision for each file at the latest version date
        String sql = """
            SELECT DISTINCT f.file_path,
                   (SELECT fr2.id 
                    FROM file_revisions fr2 
                    INNER JOIN commits c2 ON fr2.commit_hash = c2.commit_hash 
                    WHERE fr2.file_path = f.file_path 
                    AND c2.commit_date <= ? 
                    ORDER BY c2.commit_date DESC 
                    LIMIT 1) as latest_revision_id,
                   ? as commit_date
            FROM file_revisions f
            INNER JOIN commits c ON f.commit_hash = c.commit_hash
            WHERE f.file_path LIKE '%' || ? || '%'
            AND c.commit_date <= ?
            """;
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, latestVersionDate);
            stmt.setString(2, latestVersionDate);
            stmt.setString(3, fileExtensions);
            stmt.setString(4, latestVersionDate);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Long revisionId = rs.getLong("latest_revision_id");
                    if (!rs.wasNull()) {
                        LocalDateTime commitDate = parseCommitDate(rs.getString("commit_date"));
                        // NO BUG LABEL - this is what we want to predict!
                        fileRevisions.put(revisionId, new FileRevisionData(revisionId, commitDate, false)); // dummy value
                    }
                }
            }
        }
        
        return fileRevisions;
    }
    
    /**
     * Gets revision IDs for prediction tracking
     */
    private List<Long> getPredictionRevisionIds(String latestVersionCommit) throws SQLException {
        String latestVersionDate = getCommitDate(latestVersionCommit);
        if (latestVersionDate == null) {
            return new ArrayList<>();
        }
        
        Map<Long, FileRevisionData> latestRevisions = loadLatestVersionFileRevisions(latestVersionDate);
        return new ArrayList<>(latestRevisions.keySet());
    }
    
    /**
     * Populates prediction dataset with missing class values (to be predicted)
     */
    private void populatePredictionDataset(Instances dataset, List<FileRevisionData> revisions) {
        instanceToRevisionMap.clear(); // Clear for prediction instances
        
        for (FileRevisionData revision : revisions) {
            double[] values = new double[dataset.numAttributes()];
            int attrIndex = 0;
            
            // Add process metrics
            for (String metric : PROCESS_METRICS) {
                values[attrIndex++] = revision.metrics.getOrDefault(metric, 0.0);
            }
            
            // Add static metrics with defaults
            for (String metric : STATIC_METRICS) {
                values[attrIndex++] = revision.metrics.getOrDefault(metric, getDefaultStaticMetricValue(metric));
            }
            
            // Add derived metrics
            double linesAdded = revision.metrics.getOrDefault("LINES_ADDED", 0.0);
            double linesDeleted = revision.metrics.getOrDefault("LINES_DELETED", 0.0);
            double ncssClass = revision.metrics.getOrDefault("NCSS_CLASS", getDefaultStaticMetricValue("NCSS_CLASS"));
            
            values[attrIndex++] = linesAdded - linesDeleted; // CHANGE_DELTA
            values[attrIndex++] = ncssClass > 0 ? (linesAdded + linesDeleted) / ncssClass : 0.0; // RELATIVE_CHURN
            values[attrIndex++] = (linesAdded + linesDeleted) > 0 ? linesDeleted / (linesAdded + linesDeleted) : 0.0; // DELETION_RATIO
            
            // Class value - SET TO MISSING (this is what we want to predict!)
            values[attrIndex] = Double.NaN; // MISSING VALUE FOR PREDICTION!
            
            Instance instance = new DenseInstance(1.0, values);
            instance.setDataset(dataset);
            dataset.add(instance);
            
            // Track the mapping for this prediction instance
            instanceToRevisionMap.put(instance, revision);
        }
    }
    
    /**
     * Saves training and prediction datasets as separate ARFF files
     */
    private void saveTrainingAndPredictionArff(Instances trainingSet, Instances predictionSet) throws IOException {
        String projectName = extractProjectNameFromConfig();
        
        // Save training dataset
        String trainingFilename = projectName + "-training.arff";
        ArffSaver trainingSaver = new ArffSaver();
        trainingSaver.setInstances(trainingSet);
        trainingSaver.setFile(new File(trainingFilename));
        trainingSaver.writeBatch();
        System.out.println("✓ Training dataset saved to " + trainingFilename);
        
        // Save prediction dataset
        String predictionFilename = projectName + "-prediction.arff";
        ArffSaver predictionSaver = new ArffSaver();
        predictionSaver.setInstances(predictionSet);
        predictionSaver.setFile(new File(predictionFilename));
        predictionSaver.writeBatch();
        System.out.println("✓ Prediction dataset saved to " + predictionFilename);
        
        // Print summary
        System.out.println("📁 ARFF Files Summary:");
        System.out.println("   🔵 " + projectName + "-dataset.arff: Complete historical data (" + 
                          (trainingSet.numInstances() + predictionSet.numInstances()) + " instances)");
        System.out.println("   🟢 " + trainingFilename + ": Training data with bug labels (" + 
                          trainingSet.numInstances() + " instances)");
        System.out.println("   🔴 " + predictionFilename + ": Prediction data without bug labels (" + 
                          predictionSet.numInstances() + " instances)");
    }

    /**
     * Parse commit date string to LocalDateTime, handling both formats
     */
    private LocalDateTime parseCommitDate(String dateStr) {
        if (dateStr == null) {
            return null;
        }
        
        try {
            // First try direct parsing if already in ISO format (T separator)
            return LocalDateTime.parse(dateStr);
        } catch (DateTimeParseException e) {
            try {
                // Fallback: try with space replaced by T (for legacy data)
                return LocalDateTime.parse(dateStr.replace(" ", "T"));
            } catch (DateTimeParseException e2) {
                System.err.println("Failed to parse date: " + dateStr);
                e2.printStackTrace();
                return null;
            }
        }
    }
}
