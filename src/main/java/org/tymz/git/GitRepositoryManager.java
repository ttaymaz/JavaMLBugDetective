package org.tymz.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.tymz.config.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Git repository manager for JavaMLBugDetective.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 *
 * This class is responsible for high-level Git repository operations including
 * cloning, loading, and basic commit history traversal.
 * 
 * Adheres to the Single Responsibility Principle - only handles repository
 * management operations. Individual commit and file analysis is delegated
 * to other specialized classes.
 */
public class GitRepositoryManager {
    
    private static final String GIT_DIR = ".git";
    
    /**
     * Load an existing local repository or clone it from remote if it doesn't exist.
     * 
     * This method checks if a Git repository exists at the local path specified
     * in the configuration. If it exists, it opens the existing repository.
     * If not, it clones the repository from the remote URL specified in the config.
     * 
     * @return the loaded or cloned JGit Repository object
     * @throws IOException if file system operations fail
     * @throws GitAPIException if Git operations fail
     * @throws IllegalStateException if required configuration is missing
     */
    public Repository loadOrCloneRepository() throws IOException, GitAPIException {
        String repositoryUrl = Config.getRepositoryUrl();
        String localPath = Config.getRepositoryLocalPath();
        
        // Validate configuration
        if (repositoryUrl == null || repositoryUrl.trim().isEmpty()) {
            throw new IllegalStateException("Repository URL is not configured. Please set 'repository.url' in config.properties");
        }
        
        if (localPath == null || localPath.trim().isEmpty()) {
            throw new IllegalStateException("Local repository path is not configured. Please set 'repository.local.path' in config.properties");
        }
        
        Path localRepoPath = Paths.get(localPath);
        Path gitDir = localRepoPath.resolve(GIT_DIR);
        
        if (Files.exists(gitDir) && Files.isDirectory(gitDir)) {
            // Repository exists locally, open it
            if (Config.isLoggingVerbose()) {
                System.out.println("Found existing repository at: " + localPath);
            }
            return openExistingRepository(localRepoPath);
        } else {
            // Repository doesn't exist, clone it
            if (Config.isLoggingVerbose()) {
                System.out.println("Cloning repository from: " + repositoryUrl + " to: " + localPath);
            }
            return cloneRepository(repositoryUrl, localRepoPath);
        }
    }
    
    /**
     * Open an existing Git repository from the local file system.
     * 
     * @param localRepoPath the path to the local repository
     * @return the opened Repository object
     * @throws IOException if the repository cannot be opened
     */
    private Repository openExistingRepository(Path localRepoPath) throws IOException {
        FileRepositoryBuilder repositoryBuilder = new FileRepositoryBuilder();
        Repository repository = repositoryBuilder
                .setGitDir(localRepoPath.resolve(GIT_DIR).toFile())
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir() // scan up the file system tree
                .setMustExist(true)
                .build();
        
        if (Config.isLoggingVerbose()) {
            System.out.println("Successfully opened existing repository: " + repository.getDirectory());
        }
        
        return repository;
    }
    
    /**
     * Clone a Git repository from a remote URL to a local path.
     * 
     * @param repositoryUrl the remote repository URL
     * @param localRepoPath the local path where the repository should be cloned
     * @return the cloned Repository object
     * @throws GitAPIException if cloning fails
     * @throws IOException if file system operations fail
     */
    private Repository cloneRepository(String repositoryUrl, Path localRepoPath) throws GitAPIException, IOException {
        // Ensure parent directories exist
        Files.createDirectories(localRepoPath.getParent());
        
        // Create clone command
        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(localRepoPath.toFile())
                .setCloneAllBranches(false) // Only clone the default branch for efficiency
                .setCloneSubmodules(false); // Skip submodules for simplicity
        
        // Configure authentication for private repositories if credentials are provided
        String githubUsername = Config.getGithubUsername();
        String githubToken = Config.getGithubToken();
        
        if (githubUsername != null && githubToken != null) {
            if (Config.isLoggingVerbose()) {
                System.out.println("Using GitHub authentication for private repository access");
            }
            cloneCommand.setCredentialsProvider(
                new UsernamePasswordCredentialsProvider(githubUsername, githubToken)
            );
        } else {
            if (Config.isLoggingVerbose()) {
                System.out.println("No GitHub credentials configured - accessing as public repository");
            }
        }
        
        // Clone the repository
        Git git = cloneCommand.call();
        
        Repository repository = git.getRepository();
        
        if (Config.isLoggingVerbose()) {
            System.out.println("Successfully cloned repository from: " + repositoryUrl);
            System.out.println("Repository location: " + repository.getDirectory());
        }
        
        return repository;
    }
    
    /**
     * Retrieve all commits from the repository's default branch.
     * 
     * This method traverses the entire commit history using JGit's RevWalk,
     * starting from the HEAD commit and working backwards through the history.
     * The commits are returned in reverse chronological order (newest first).
     * 
     * @param repository the Git repository to traverse
     * @return a List of RevCommit objects ordered from newest to oldest
     * @throws IOException if repository access fails
     * @throws IllegalArgumentException if repository is null
     */
    public List<RevCommit> getAllCommits(Repository repository) throws IOException {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        
        List<RevCommit> commits = new ArrayList<>();
        
        try (RevWalk revWalk = new RevWalk(repository)) {
            // Start from HEAD (most recent commit)
            RevCommit headCommit = revWalk.parseCommit(repository.resolve("HEAD"));
            revWalk.markStart(headCommit);
            
            // Traverse all commits in reverse chronological order
            for (RevCommit commit : revWalk) {
                commits.add(commit);
            }
        }
        
        if (Config.isLoggingVerbose()) {
            System.out.println("Retrieved " + commits.size() + " commits from repository");
        }
        
        return commits;
    }
    
    /**
     * Get basic repository information for logging and debugging purposes.
     * 
     * @param repository the repository to get information about
     * @return a string containing basic repository information
     * @throws IOException if repository access fails
     */
    public String getRepositoryInfo(Repository repository) throws IOException {
        if (repository == null) {
            return "Repository: null";
        }
        
        StringBuilder info = new StringBuilder();
        info.append("Repository Information:\n");
        info.append("  Directory: ").append(repository.getDirectory()).append("\n");
        info.append("  Work Tree: ").append(repository.getWorkTree()).append("\n");
        info.append("  Branch: ").append(repository.getBranch()).append("\n");
        
        // Get HEAD commit info
        try {
            RevCommit headCommit = repository.parseCommit(repository.resolve("HEAD"));
            info.append("  HEAD Commit: ").append(headCommit.getId().getName()).append("\n");
            info.append("  HEAD Message: ").append(headCommit.getShortMessage()).append("\n");
        } catch (IOException e) {
            info.append("  HEAD Commit: Unable to retrieve (").append(e.getMessage()).append(")\n");
        }
        
        return info.toString();
    }
    
    /**
     * Check if a directory contains a valid Git repository.
     * 
     * @param path the path to check
     * @return true if the path contains a valid Git repository, false otherwise
     */
    public static boolean isValidGitRepository(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        
        Path gitDir = path.resolve(GIT_DIR);
        return Files.exists(gitDir) && Files.isDirectory(gitDir);
    }
    
    /**
     * Close a repository and release its resources.
     * 
     * @param repository the repository to close
     */
    public void closeRepository(Repository repository) {
        if (repository != null) {
            repository.close();
            if (Config.isLoggingVerbose()) {
                System.out.println("Repository closed: " + repository.getDirectory());
            }
        }
    }
    
    /**
     * Extracts the repository name from the local repository path.
     * This is used for dynamic report filename generation.
     * 
     * @return the repository name (e.g., "gson" from "./repositories/gson")
     */
    public String getRepoName() {
        String localPath = Config.getRepositoryLocalPath();
        if (localPath == null || localPath.isEmpty()) {
            return "unknown-repo";
        }
        
        // Extract the last directory name from the path
        Path path = Paths.get(localPath);
        Path fileName = path.getFileName();
        
        return fileName != null ? fileName.toString() : "unknown-repo";
    }
}
