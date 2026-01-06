package org.tymz.config;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Config class.
 * Tests configuration loading and property access methods.
 * 
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class ConfigTest {

    @BeforeAll
    static void setUpBeforeClass() {
        // Ensure config is initialized before all tests
        try {
            // Config is automatically initialized via static block
            // But we can add a small delay to ensure it's loaded
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Should load properties successfully from config.properties file")
    void testLoadPropertiesSuccessfully() {
        // Test that the Config class initializes properly
        assertTrue(Config.isInitialized(), "Configuration should be initialized successfully");
        
        // Test that we can access at least one property without error
        assertDoesNotThrow(() -> Config.getDatabaseName(), 
                          "Should be able to access database name property");
        
        // Verify that the loaded property is not null
        assertNotNull(Config.getDatabaseName(), "Database name should not be null after loading");
    }

    @Test
    @DisplayName("Should get property as string correctly")
    void testGetPropertyAsString() {
        // Test getting a known string property
        String databaseName = Config.getDatabaseName();
        assertNotNull(databaseName, "Database name should not be null");
        assertFalse(databaseName.trim().isEmpty(), "Database name should not be empty");
        
        // Test getting project name
        String projectName = Config.getProjectName();
        assertNotNull(projectName, "Project name should not be null");
        
        // Test getting a property with default value
        String testProperty = Config.getProperty("nonexistent.property", "default_value");
        assertEquals("default_value", testProperty, "Should return default value for non-existent property");
        
        // Test getting a property without default (should return null if not found)
        assertDoesNotThrow(() -> Config.getProperty("definitely.nonexistent.property"),
                          "Should not throw exception when getting non-existent property");
    }

    @Test
    @DisplayName("Should get property as integer correctly")
    void testGetPropertyAsInt() {
        // Test getting database timeout as integer
        int databaseTimeout = Config.getDatabaseTimeout();
        assertTrue(databaseTimeout > 0, "Database timeout should be a positive integer");
        
        // Test getting max commits
        int maxCommits = Config.getMaxCommits();
        assertTrue(maxCommits >= 0, "Max commits should be non-negative");
        
        // Test getting ML CV folds
        int cvFolds = Config.getMLCVFolds();
        assertTrue(cvFolds > 0, "CV folds should be a positive integer");
        
        // Test getting a non-existent integer property with default
        int defaultInt = Config.getIntProperty("nonexistent.int.property", 42);
        assertEquals(42, defaultInt, "Should return default value for non-existent integer property");
        
        // Test getting JVM memory max
        int jvmMemory = Config.getJVMMemoryMax();
        assertTrue(jvmMemory > 0, "JVM memory max should be positive");
    }

    @Test
    @DisplayName("Should get property as boolean correctly")
    void testGetPropertyAsBoolean() {
        // Test getting logging verbose setting - should not throw exception
        assertDoesNotThrow(() -> Config.isLoggingVerbose(), 
                          "Should not throw exception when getting boolean property");
        
        // Test getting ML balance classes setting - should not throw exception
        assertDoesNotThrow(() -> Config.isMLBalanceClasses(),
                          "Should not throw exception when getting ML balance classes property");
        
        // Test getting report include details setting - should not throw exception
        assertDoesNotThrow(() -> Config.isReportIncludeDetails(),
                          "Should not throw exception when getting report include details property");
        
        // Test getting a non-existent boolean property with default true
        boolean defaultTrue = Config.getBooleanProperty("nonexistent.bool.property", true);
        assertTrue(defaultTrue, "Should return default true value for non-existent boolean property");
        
        // Test getting a non-existent boolean property with default false
        boolean defaultFalse = Config.getBooleanProperty("another.nonexistent.bool.property", false);
        assertFalse(defaultFalse, "Should return default false value for non-existent boolean property");
    }

    @Test
    @DisplayName("Should return default value when requested property does not exist")
    void testGetPropertyWithDefaultValue() {
        // Test string property with default
        String stringDefault = Config.getProperty("missing.string.property", "default_string");
        assertEquals("default_string", stringDefault, 
                    "Should return default string value for missing property");
        
        // Test integer property with default
        int intDefault = Config.getIntProperty("missing.int.property", 999);
        assertEquals(999, intDefault, 
                    "Should return default integer value for missing property");
        
        // Test double property with default
        double doubleDefault = Config.getDoubleProperty("missing.double.property", 3.14);
        assertEquals(3.14, doubleDefault, 0.001, 
                    "Should return default double value for missing property");
        
        // Test boolean property with default
        boolean boolDefault = Config.getBooleanProperty("missing.bool.property", true);
        assertTrue(boolDefault, 
                  "Should return default boolean value for missing property");
        
        // Test that existing properties still return their actual values, not defaults
        String actualProjectName = Config.getProperty("project.name", "should_not_return_this");
        assertNotEquals("should_not_return_this", actualProjectName,
                       "Should return actual property value, not default, when property exists");
    }

    @Test
    @DisplayName("Should handle missing required properties appropriately")
    void testMissingRequiredProperty() {
        // Test that getting a property without a default returns null (not throwing exception)
        assertDoesNotThrow(() -> Config.getProperty("absolutely.nonexistent.property"),
                          "Getting a non-existent property should not throw exception");
        
        // Test that core required properties are available and not null
        assertNotNull(Config.getDatabaseName(), "Database name should be available as required property");
        assertNotNull(Config.getProjectName(), "Project name should be available as required property");
        
        // Test that numeric properties have reasonable defaults when missing
        assertTrue(Config.getDatabaseTimeout() > 0, 
                  "Database timeout should have a reasonable default value");
        assertTrue(Config.getJVMMemoryMax() > 0, 
                  "JVM memory max should have a reasonable default value");
        
        // Test that double properties return reasonable values
        double trainRatio = Config.getMLTrainRatio();
        assertTrue(trainRatio > 0 && trainRatio <= 1.0, 
                  "ML train ratio should be a valid proportion");
    }

    @Test
    @DisplayName("Should handle property type conversions correctly")
    void testPropertyTypeConversions() {
        // Test that invalid numeric strings are handled gracefully
        int invalidIntDefault = Config.getIntProperty("invalid.numeric.property", 100);
        assertEquals(100, invalidIntDefault, 
                    "Should return default when property contains invalid integer");
        
        double invalidDoubleDefault = Config.getDoubleProperty("invalid.double.property", 1.5);
        assertEquals(1.5, invalidDoubleDefault, 0.001,
                    "Should return default when property contains invalid double");
        
        // Test boolean conversion with various string values - should not throw exceptions
        assertDoesNotThrow(() -> Config.getBooleanProperty("test.bool.true", false),
                          "Boolean property access should not throw exception");
        assertDoesNotThrow(() -> Config.getBooleanProperty("test.bool.false", true),
                          "Boolean property access should not throw exception");
        
        // These should work regardless of the actual values in config
        assertDoesNotThrow(() -> Config.getBooleanProperty("any.property", true),
                          "Boolean property conversion should not throw exception");
    }

    @Test
    @DisplayName("Should maintain consistent property access across multiple calls")
    void testConsistentPropertyAccess() {
        // Test that multiple calls to the same property return consistent results
        String dbName1 = Config.getDatabaseName();
        String dbName2 = Config.getDatabaseName();
        assertEquals(dbName1, dbName2, "Multiple calls should return consistent values");
        
        int timeout1 = Config.getDatabaseTimeout();
        int timeout2 = Config.getDatabaseTimeout();
        assertEquals(timeout1, timeout2, "Multiple calls to integer property should be consistent");
        
        boolean verbose1 = Config.isLoggingVerbose();
        boolean verbose2 = Config.isLoggingVerbose();
        assertEquals(verbose1, verbose2, "Multiple calls to boolean property should be consistent");
        
        double ratio1 = Config.getMLTrainRatio();
        double ratio2 = Config.getMLTrainRatio();
        assertEquals(ratio1, ratio2, 0.001, "Multiple calls to double property should be consistent");
    }

    @Test
    @DisplayName("Should handle edge cases and boundary values")
    void testEdgeCasesAndBoundaryValues() {
        // Test with empty string property name
        assertDoesNotThrow(() -> Config.getProperty("", "default"),
                          "Should handle empty property name gracefully");
        
        // Test with null default values (where applicable) - should handle gracefully
        assertDoesNotThrow(() -> Config.getProperty("nonexistent", null),
                          "Should handle null default value gracefully");
        
        // Test numeric boundaries
        assertTrue(Config.getMLTrainRatio() >= 0.0 && Config.getMLTrainRatio() <= 1.0,
                  "Train ratio should be within valid bounds");
        
        assertTrue(Config.getMLCVFolds() >= 1, "CV folds should be at least 1");
        
        // Test that string properties are trimmed or handled appropriately
        assertNotNull(Config.getReportFormat(), "Report format should not be null");
        assertNotNull(Config.getMLAlgorithm(), "ML algorithm should not be null");
    }

    @Test
    @DisplayName("Should handle GitHub authentication configuration correctly")
    void testGithubAuthenticationConfig() {
        // Test GitHub username property
        // Since we don't expect these to be set in test environment, they should return null
        String githubUsername = Config.getGithubUsername();
        // This will be null for empty configuration values as per the implementation
        assertNull(githubUsername, "GitHub username should be null when not configured");
        
        // Test GitHub token property
        String githubToken = Config.getGithubToken();
        // This will be null for empty configuration values as per the implementation
        assertNull(githubToken, "GitHub token should be null when not configured");
        
        // Test that methods handle the properties gracefully
        assertDoesNotThrow(() -> Config.getGithubUsername(),
                          "Should handle GitHub username property access gracefully");
        assertDoesNotThrow(() -> Config.getGithubToken(),
                          "Should handle GitHub token property access gracefully");
    }
}
