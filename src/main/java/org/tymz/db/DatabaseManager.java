package org.tymz.db;

import org.tymz.config.Config;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Database manager for JavaMLBugDetective using SQLite.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * This class implements the Singleton pattern and is robust against connection closures
 * that might occur during test cycles.
 */
public class DatabaseManager {

    private static DatabaseManager instance;
    private Connection connection;
    private final String databasePath;

    // SQL statements for table creation (TAM VE DOĞRU HALLERİ)
    private static final String CREATE_COMMITS_TABLE = """
            CREATE TABLE IF NOT EXISTS commits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                commit_hash TEXT UNIQUE NOT NULL,
                author_name TEXT NOT NULL,
                author_email TEXT NOT NULL,
                commit_date DATETIME NOT NULL,
                message TEXT NOT NULL,
                is_bug_fix BOOLEAN DEFAULT FALSE,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String CREATE_FILE_REVISIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS file_revisions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL,
                commit_hash TEXT NOT NULL,
                change_type TEXT NOT NULL CHECK (change_type IN ('ADD', 'MODIFY', 'DELETE', 'RENAME')),
                lines_added INTEGER DEFAULT 0,
                lines_deleted INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (commit_hash) REFERENCES commits(commit_hash),
                UNIQUE(file_path, commit_hash)
            )
            """;

    private static final String CREATE_METRICS_TABLE = """
            CREATE TABLE IF NOT EXISTS metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_revision_id INTEGER NOT NULL,
                metric_name TEXT NOT NULL,
                metric_value REAL NOT NULL,
                metric_type TEXT NOT NULL CHECK (metric_type IN ('PROCESS', 'STATIC')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (file_revision_id) REFERENCES file_revisions(id),
                UNIQUE(file_revision_id, metric_name)
            )
            """;

    private static final String CREATE_BUG_LABELS_TABLE = """
            CREATE TABLE IF NOT EXISTS bug_labels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_revision_id INTEGER UNIQUE NOT NULL,
                is_buggy BOOLEAN NOT NULL,
                confidence_score REAL DEFAULT 1.0,
                labeled_by TEXT DEFAULT 'SZZ_ALGORITHM',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (file_revision_id) REFERENCES file_revisions(id)
            )
            """;

    private static final String[] INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_commits_hash ON commits(commit_hash)",
            "CREATE INDEX IF NOT EXISTS idx_commits_date ON commits(commit_date)",
            "CREATE INDEX IF NOT EXISTS idx_file_revisions_path ON file_revisions(file_path)",
            "CREATE INDEX IF NOT EXISTS idx_file_revisions_commit ON file_revisions(commit_hash)",
            "CREATE INDEX IF NOT EXISTS idx_metrics_revision ON metrics(file_revision_id)",
            "CREATE INDEX IF NOT EXISTS idx_metrics_name ON metrics(metric_name)",
            "CREATE INDEX IF NOT EXISTS idx_bug_labels_revision ON bug_labels(file_revision_id)"
    };

    private DatabaseManager() {
        this.databasePath = Config.getDatabaseName();
        try {
            initializeConnection();
            initializeSchema();
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Get the singleton instance of DatabaseManager.
     * This method is now robust and will re-initialize the connection if it was closed.
     * @return the singleton DatabaseManager instance
     */
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        } else {
            try {
                // **İŞTE NİHAİ ÇÖZÜM**
                // Testler tarafından kapatılmış olabilecek bağlantıyı kontrol et.
                if (instance.connection == null || instance.connection.isClosed()) {
                    System.out.println("WARNING: Database connection was found closed. Re-initializing for main application run...");
                    // Bağlantıyı ve şemayı yeniden başlat.
                    instance.initializeConnection();
                    instance.initializeSchema();
                }
            } catch (SQLException e) {
                System.err.println("Failed to re-initialize database connection: " + e.getMessage());
                throw new RuntimeException("Database re-initialization failed", e);
            }
        }
        return instance;
    }

    private void initializeConnection() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + databasePath;
            connection = DriverManager.getConnection(url);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("PRAGMA foreign_keys = ON");
                stmt.execute("PRAGMA synchronous = NORMAL");
                stmt.execute("PRAGMA cache_size = 10000");
                stmt.execute("PRAGMA temp_store = MEMORY");
            }
            connection.setAutoCommit(false);
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
    }

    public void initializeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_COMMITS_TABLE);
            stmt.execute(CREATE_FILE_REVISIONS_TABLE);
            stmt.execute(CREATE_METRICS_TABLE);
            stmt.execute(CREATE_BUG_LABELS_TABLE);
            for (String indexSql : INDEXES) {
                stmt.execute(indexSql);
            }
            connection.commit();
        } catch (SQLException e) {
            if(connection != null) connection.rollback();
            throw e;
        }
    }

    public long insertCommit(String commitHash, String authorName, String authorEmail, 
                            LocalDateTime commitDate, String message, boolean isBugFix) throws SQLException {
        String sql = "INSERT OR IGNORE INTO commits (commit_hash, author_name, author_email, commit_date, message, is_bug_fix) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, commitHash);
            pstmt.setString(2, authorName);
            pstmt.setString(3, authorEmail);
            pstmt.setObject(4, commitDate);
            pstmt.setString(5, message);
            pstmt.setBoolean(6, isBugFix);
            if (pstmt.executeUpdate() > 0) {
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
            return getCommitId(commitHash);
        }
    }

    public long insertFileRevision(String filePath, String commitHash, String changeType, 
                                  int linesAdded, int linesDeleted) throws SQLException {
        String normalizedChangeType = changeType.toUpperCase();
        if (!Arrays.asList("ADD", "MODIFY", "DELETE", "RENAME").contains(normalizedChangeType)) {
            throw new SQLException("Invalid change type: " + changeType);
        }
        String sql = "INSERT OR IGNORE INTO file_revisions (file_path, commit_hash, change_type, lines_added, lines_deleted) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, filePath);
            pstmt.setString(2, commitHash);
            pstmt.setString(3, normalizedChangeType);
            pstmt.setInt(4, linesAdded);
            pstmt.setInt(5, linesDeleted);
            if (pstmt.executeUpdate() > 0) {
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
            return getFileRevisionId(filePath, commitHash);
        }
    }

    public void insertMetric(long fileRevisionId, String metricName, double metricValue, 
                           String metricType) throws SQLException {
        String normalizedMetricType = metricType.toUpperCase();
        if (!Arrays.asList("PROCESS", "STATIC").contains(normalizedMetricType)) {
            throw new SQLException("Invalid metric type: " + metricType);
        }
        String sql = "INSERT OR REPLACE INTO metrics (file_revision_id, metric_name, metric_value, metric_type) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, fileRevisionId);
            pstmt.setString(2, metricName);
            pstmt.setDouble(3, metricValue);
            pstmt.setString(4, normalizedMetricType);
            pstmt.executeUpdate();
        }
    }

    public void insertBugLabel(long fileRevisionId, boolean isBuggy, double confidenceScore, 
                              String labeledBy) throws SQLException {
        String sql = "INSERT OR REPLACE INTO bug_labels (file_revision_id, is_buggy, confidence_score, labeled_by) VALUES (?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, fileRevisionId);
            pstmt.setBoolean(2, isBuggy);
            pstmt.setDouble(3, confidenceScore);
            pstmt.setString(4, labeledBy);
            pstmt.executeUpdate();
        }
    }

    public long getCommitId(String commitHash) throws SQLException {
        String sql = "SELECT id FROM commits WHERE commit_hash = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, commitHash);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return -1;
    }

    public long getFileRevisionId(String filePath, String commitHash) throws SQLException {
        String sql = "SELECT id FROM file_revisions WHERE file_path = ? AND commit_hash = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filePath);
            pstmt.setString(2, commitHash);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getLong("id");
            }
        }
        return -1;
    }
    
    public List<String> getAllFilePaths() throws SQLException {
        String sql = "SELECT DISTINCT file_path FROM file_revisions ORDER BY file_path";
        List<String> filePaths = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                filePaths.add(rs.getString("file_path"));
            }
        }
        return filePaths;
    }

    public int getCommitCount() throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM commits";
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) return rs.getInt("count");
        }
        return 0;
    }

    public int getFileRevisionCount() throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM file_revisions";
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) return rs.getInt("count");
        }
        return 0;
    }

    public boolean commitExists(String commitHash) throws SQLException {
        return getCommitId(commitHash) != -1;
    }

    public boolean fileRevisionExists(String filePath, String commitHash) throws SQLException {
        return getFileRevisionId(filePath, commitHash) != -1;
    }

    public void commit() throws SQLException {
        if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) {
            connection.commit();
        }
    }

    public void rollback() throws SQLException {
        if (connection != null && !connection.getAutoCommit()) {
            connection.rollback();
        }
    }

    public void beginTransaction() throws SQLException {
        if (connection != null) {
            connection.setAutoCommit(false);
        }
    }

    public void endTransaction() throws SQLException {
        if (connection != null) {
            connection.setAutoCommit(true);
        }
    }

    public Connection getConnection() throws SQLException {
        // Check if connection is closed and re-initialize if needed
        if (connection == null || connection.isClosed()) {
            System.out.println("WARNING: Database connection was found closed. Re-initializing...");
            initializeConnection();
        }
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            try {
                if (!connection.getAutoCommit()) {
                    connection.commit();
                }
            } catch (SQLException e) {
                System.err.println("Error committing final transaction: " + e.getMessage());
            } finally {
                connection.close();
            }
        }
    }

    public String getDatabaseStats() throws SQLException {
        StringBuilder stats = new StringBuilder();
        stats.append("Database Statistics:\n");
        stats.append("- Total Commits: ").append(getCommitCount()).append("\n");
        stats.append("- Total File Revisions: ").append(getFileRevisionCount()).append("\n");
        
        String sql = "SELECT COUNT(*) as count FROM metrics";
        try (PreparedStatement pstmt = connection.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) stats.append("- Total Metrics: ").append(rs.getInt("count")).append("\n");
        }
        
        sql = "SELECT COUNT(*) as count FROM bug_labels";
        try (PreparedStatement pstmt = connection.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) stats.append("- Total Bug Labels: ").append(rs.getInt("count")).append("\n");
        }
        
        return stats.toString();
    }
}