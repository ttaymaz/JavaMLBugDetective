package org.tymz.szz;

import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tymz.db.DatabaseManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the SZZBugLabeler class.
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
class SZZBugLabelerTest {

    private SZZBugLabeler szzLabeler;
    private DatabaseManager mockDatabaseManager;

    @BeforeEach
    void setUp() {
        mockDatabaseManager = mock(DatabaseManager.class);
        szzLabeler = new SZZBugLabeler(mockDatabaseManager); // Use new constructor
    }

    @Test
    @DisplayName("Constructor should initialize without errors")
    void testConstructorInitialization() {
        assertDoesNotThrow(() -> new SZZBugLabeler(mockDatabaseManager));
        assertNotNull(szzLabeler);
    }

    @Test
    @DisplayName("Should handle an empty commit list gracefully")
    void testLabelBugsWithEmptyList() {
        Repository mockRepo = mock(Repository.class);
        assertDoesNotThrow(() -> szzLabeler.labelBugs(mockRepo, Collections.emptyList()),
            "Should not throw an exception for an empty commit list.");
    }
    
    @Test
    @DisplayName("Should handle null database manager during initialization")
    void testInitializationWithDbError() {
        DatabaseManager invalidDbManager = null;
        
        // Constructor accepts null, but it would fail when using databaseManager methods
        assertDoesNotThrow(() -> new SZZBugLabeler(invalidDbManager));
        
        // Verify that the labeler was created (constructor doesn't validate null)
        SZZBugLabeler labeler = new SZZBugLabeler(invalidDbManager);
        assertNotNull(labeler);
    }
}