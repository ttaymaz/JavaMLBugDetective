package org.tymz.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration manager for JavaMLBugDetective.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 * 
 * This class provides static access to all configuration properties
 * loaded from the config.properties file.
 * 
 * All configuration values are loaded once during class initialization
 * and can be accessed through static getter methods.
 *
 */
public class Config {

    private static final Properties properties = new Properties();
    private static boolean initialized = false;

    // Configuration file path
    private static final String CONFIG_FILE = "config.properties";

    // Static initialization block
    static {
        loadConfiguration();
    }

    /**
     * Load configuration from the config.properties file.
     * This method is called automatically during class initialization.
     */
    private static void loadConfiguration() {
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            properties.load(fis);
            initialized = true;
            System.out.println("Configuration loaded successfully from " + CONFIG_FILE);
        } catch (IOException e) {
            System.err.println("Error loading configuration file: " + e.getMessage());
            System.err.println("Make sure " + CONFIG_FILE + " exists in the project root directory.");
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * Check if configuration has been successfully initialized.
     * 
     * @return true if configuration is loaded, false otherwise
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Get a property value as String.
     * 
     * @param key the property key
     * @return the property value, or null if not found
     */
    public static String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Get a property value as String with a default value.
     * 
     * @param key the property key
     * @param defaultValue the default value if property is not found
     * @return the property value or default value
     */
    public static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Get a property value as integer.
     * 
     * @param key the property key
     * @param defaultValue the default value if property is not found or invalid
     * @return the property value as integer
     */
    public static int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer value for property " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    /**
     * Get a property value as double.
     * 
     * @param key the property key
     * @param defaultValue the default value if property is not found or invalid
     * @return the property value as double
     */
    public static double getDoubleProperty(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid double value for property " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    /**
     * Get a property value as boolean.
     * 
     * @param key the property key
     * @param defaultValue the default value if property is not found
     * @return the property value as boolean
     */
    public static boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    // Convenient getter methods for specific configuration values

    /**
     * Get the repository URL to analyze.
     * 
     * @return the repository URL
     */
    public static String getRepositoryUrl() {
        return getProperty("repository.url");
    }

    /**
     * Get the local repository path.
     * 
     * @return the local repository path
     */
    public static String getRepositoryLocalPath() {
        return getProperty("repository.local.path", "./repositories/default");
    }

    /**
     * Get the project name.
     * 
     * @return the project name
     */
    public static String getProjectName() {
        return getProperty("project.name", "default");
    }

    /**
     * Get the database file name.
     * Automatically generates database name from project name.
     * 
     * @return the database file name (project.name + ".db")
     */
    public static String getDatabaseName() {
        String projectName = getProjectName();
        return projectName + ".db";
    }

    /**
     * Get the database connection timeout.
     * 
     * @return the timeout in seconds
     */
    public static int getDatabaseTimeout() {
        return getIntProperty("database.timeout", 30);
    }

    /**
     * Get the bug fix pattern for identifying bug-fixing commits.
     * 
     * @return the regex pattern
     */
    public static String getBugFixPattern() {
        return getProperty("bug.fix.pattern", "(?i)(fix|bug|defect|issue|patch)");
    }

    /**
     * Get the file extensions to analyze.
     * 
     * @return the file extensions (comma-separated)
     */
    public static String getFileExtensions() {
        return getProperty("file.extensions", ".java");
    }

    /**
     * Get the maximum number of commits to analyze.
     * 
     * @return the maximum commits (0 = all commits)
     */
    public static int getMaxCommits() {
        return getIntProperty("max.commits", 0);
    }

    /**
     * Get the ML train/test split ratio.
     * 
     * @return the train ratio (e.g., 0.8 for 80% training)
     */
    public static double getMLTrainRatio() {
        return getDoubleProperty("ml.train.ratio", 0.8);
    }

    /**
     * Get the ML algorithm to use.
     * 
     * @return the ML algorithm name
     */
    public static String getMLAlgorithm() {
        return getProperty("ml.algorithm", "RandomForest");
    }

    /**
     * Get the number of cross-validation folds.
     * 
     * @return the number of CV folds
     */
    public static int getMLCVFolds() {
        return getIntProperty("ml.cv.folds", 10);
    }

    /**
     * Check if class balancing is enabled.
     * 
     * @return true if class balancing is enabled
     */
    public static boolean isMLBalanceClasses() {
        return getBooleanProperty("ml.balance.classes", true);
    }

    /**
     * Get the report output directory.
     * 
     * @return the output directory path
     */
    public static String getReportOutputDir() {
        return getProperty("report.output.dir", "./reports");
    }

    /**
     * Get the report format.
     * 
     * @return the report format
     */
    public static String getReportFormat() {
        return getProperty("report.format", "HTML");
    }

    /**
     * Check if detailed metrics should be included in reports.
     * 
     * @return true if details should be included
     */
    public static boolean isReportIncludeDetails() {
        return getBooleanProperty("report.include.details", true);
    }

    /**
     * Check if verbose logging is enabled.
     * 
     * @return true if verbose logging is enabled
     */
    public static boolean isLoggingVerbose() {
        return getBooleanProperty("logging.verbose", false);
    }

    /**
     * Get the maximum JVM memory allocation.
     * 
     * @return the memory allocation in MB
     */
    public static int getJVMMemoryMax() {
        return getIntProperty("jvm.memory.max", 2048);
    }

    /**
     * Get the SZZ bug fix keywords.
     * 
     * @return comma-separated bug fix keywords
     */
    public static String getSZZBugFixKeywords() {
        return getProperty("szz.bug_fix_keywords", "fix,bug,issue,defect,error,fault,problem,repair");
    }

    /**
     * Get the SZZ confidence score.
     * 
     * @return the confidence score (0.0 to 1.0)
     */
    public static double getSZZConfidenceScore() {
        return getDoubleProperty("szz.confidence_score", 0.8);
    }

    /**
     * Check if comments should be ignored in SZZ blame analysis.
     * 
     * @return true if comments should be ignored
     */
    public static boolean isSZZIgnoreComments() {
        return getBooleanProperty("szz.ignore_comments", true);
    }

    /**
     * Check if blank lines should be ignored in SZZ blame analysis.
     * 
     * @return true if blank lines should be ignored
     */
    public static boolean isSZZIgnoreBlankLines() {
        return getBooleanProperty("szz.ignore_blank_lines", true);
    }

    /**
     * Get the cost of False Negative (buggy classified as clean) for cost-sensitive learning.
     * 
     * @return the False Negative cost
     */
    public static double getMLCostFN() {
        return getDoubleProperty("ml.cost.fn", 10.0); // Default to 10.0
    }

    /**
     * Get the cost of False Positive (clean classified as buggy) for cost-sensitive learning.
     * 
     * @return the False Positive cost
     */
    public static double getMLCostFP() {
        return getDoubleProperty("ml.cost.fp", 1.0);   // Default to 1.0
    }

    /**
     * Get the GitHub username for private repository access.
     * 
     * @return the GitHub username, or null if not configured
     */
    public static String getGithubUsername() {
        String username = getProperty("github.username");
        return (username != null && !username.trim().isEmpty()) ? username.trim() : null;
    }

    /**
     * Get the GitHub Personal Access Token for private repository access.
     * 
     * @return the GitHub token, or null if not configured
     */
    public static String getGithubToken() {
        String token = getProperty("github.token");
        return (token != null && !token.trim().isEmpty()) ? token.trim() : null;
    }
}
