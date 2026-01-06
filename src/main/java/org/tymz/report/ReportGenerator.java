package org.tymz.report;

import org.tymz.config.Config;
import org.tymz.ml.ModelEvaluationResult;
import weka.classifiers.Evaluation;
import weka.core.Instances;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Generates comprehensive analysis reports for bug prediction results.
 *
 * @author Turgay TAYMAZ
 * @author Assoc. Prof. Dr. Kökten Ulaş BIRANT (Advisor)
 * @version 1.0.0
 */
public class ReportGenerator {

    private final Map<String, ModelEvaluationResult> evaluationResults;
    private final Instances data;
    private final String repoName;

    public ReportGenerator(Map<String, ModelEvaluationResult> evaluationResults, Instances data, String repoName) {
        this.evaluationResults = evaluationResults;
        this.data = data;
        this.repoName = repoName;
    }

    /**
     * Generates report in the format specified by configuration
     */
    public String generateReport() throws IOException {
        String format = Config.getReportFormat();
        if ("html".equalsIgnoreCase(format)) {
            return generateHtmlReport();
        } else {
            return generateMarkdownReport();
        }
    }

    /**
     * Generates a prediction report for a specific commit
     */
    public String generatePredictionReport(String commitId, Map<String, Double> predictions) throws IOException {
        String format = Config.getReportFormat();
        if ("html".equalsIgnoreCase(format)) {
            return generatePredictionReportHtml(commitId, predictions);
        } else {
            return generatePredictionReportMarkdown(commitId, predictions);
        }
    }

    /**
     * Generates Markdown prediction report for a specific commit
     */
    private String generatePredictionReportMarkdown(String commitId, Map<String, Double> predictions) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // Clean commitId for safe filename usage by replacing problematic characters
        String safeCommitId = commitId.replaceAll("[/\\\\:*?\"<>|]", "-").substring(0, Math.min(12, commitId.length()));
        String reportFileName = String.format("./reports/%s-prediction-%s-%s.md", repoName, safeCommitId, timestamp);
        
        // Ensure reports directory exists
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./reports"));
        
        StringBuilder content = new StringBuilder();
        content.append("# Bug Prediction Report\n\n");
        content.append("**Repository:** ").append(repoName).append("\n");
        content.append("**Commit:** ").append(commitId).append("\n");
        content.append("**Generated:** ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        
        content.append("## Executive Summary\n\n");
        content.append("This report contains bug prediction results for ").append(predictions.size()).append(" files in the latest commit.\n\n");
        
        // Risk level summary
        int highRisk = 0, mediumRisk = 0, lowRisk = 0;
        for (Double probability : predictions.values()) {
            if (probability > 0.7) highRisk++;
            else if (probability > 0.4) mediumRisk++;
            else lowRisk++;
        }
        
        content.append("### Risk Distribution\n\n");
        content.append("| Risk Level | Files | Percentage |\n");
        content.append("|------------|-------|------------|\n");
        content.append(String.format("| HIGH (≥70%%) | %d | %.1f%% |\n", highRisk, (highRisk * 100.0) / predictions.size()));
        content.append(String.format("| MEDIUM (40-69%%) | %d | %.1f%% |\n", mediumRisk, (mediumRisk * 100.0) / predictions.size()));
        content.append(String.format("| LOW (<40%%) | %d | %.1f%% |\n", lowRisk, (lowRisk * 100.0) / predictions.size()));
        content.append("\n");
        
        content.append("## Predictions\n\n");
        content.append("| File | Bug Probability | Risk Level |\n");
        content.append("|------|----------------|------------|\n");
        
        // Sort predictions by probability (highest first)
        predictions.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> {
                    String file = entry.getKey();
                    Double probability = entry.getValue();
                    String riskLevel = probability > 0.7 ? "HIGH" : probability > 0.4 ? "MEDIUM" : "LOW";
                    content.append(String.format("| %s | %.3f | %s |\n", file, probability, riskLevel));
                });
        
        content.append("\n## Recommendations\n\n");
        if (highRisk > 0) {
            content.append("### 🔴 High Priority Actions\n\n");
            content.append("- Focus immediate attention on **").append(highRisk).append(" high-risk files**\n");
            content.append("- Conduct thorough code reviews for files with ≥70% bug probability\n");
            content.append("- Increase testing coverage for high-risk areas\n\n");
        }
        
        if (mediumRisk > 0) {
            content.append("### 🟡 Medium Priority Actions\n\n");
            content.append("- Monitor **").append(mediumRisk).append(" medium-risk files** closely\n");
            content.append("- Consider additional testing for files with 40-69% bug probability\n\n");
        }
        
        content.append("### 🟢 General Recommendations\n\n");
        content.append("- Continue regular code quality monitoring\n");
        content.append("- Implement automated testing for predicted high-risk areas\n");
        content.append("- Regular model retraining with new commit data\n\n");
        
        content.append("---\n\n");
        content.append("**Report Generated by JavaMLBugDetective Analysis Pipeline**\n");
        
        // Write to file
        java.nio.file.Files.writeString(java.nio.file.Paths.get(reportFileName), content.toString());
        
        System.out.println("✓ Prediction report generated: " + reportFileName);
        return reportFileName;
    }

    /**
     * Generates HTML prediction report for a specific commit
     */
    private String generatePredictionReportHtml(String commitId, Map<String, Double> predictions) throws IOException {
        // First generate Markdown
        String markdownPath = generatePredictionReportMarkdown(commitId, predictions);
        
        // Convert to HTML
        String htmlPath = markdownPath.replace(".md", ".html");
        convertMarkdownToHtml(markdownPath, htmlPath);
        
        System.out.println("✓ HTML prediction report generated: " + htmlPath);
        return htmlPath;
    }

    /**
     * Generates comprehensive Markdown report for cross-validation results
     */
    public String generateMarkdownReport() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String reportFileName = String.format("./reports/%s-report-%s.md", repoName, timestamp);
        
        // Create a simple report with the content that tests expect
        StringBuilder content = new StringBuilder();
        content.append("# Bug Prediction Analysis Report\n\n");
        content.append("**Generated on:** ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        content.append("**Project:** JavaMLBugDetective - Machine Learning Bug Prediction System\n\n");
        content.append("**Repository:** ").append(repoName).append("\n\n");
        
        // Dataset Overview section (as expected by tests)
        content.append("## Dataset Overview\n\n");
        content.append("- **Total Instances:** ").append(data.numInstances()).append("\n");
        content.append("- **Number of Features:** ").append(data.numAttributes() - 1).append(" (excluding class label)\n");
        content.append("- **Classes:** ").append(data.numClasses()).append(" (Bug/No-Bug)\n");
        content.append("- **10-Fold Cross-Validation** with SMOTE (Synthetic Minority Oversampling Technique) for advanced class balancing\n\n");
        
        // Class Distribution
        content.append("### Class Distribution Analysis\n\n");
        int[] classCounts = new int[data.numClasses()];
        for (int i = 0; i < data.numInstances(); i++) {
            classCounts[(int) data.instance(i).classValue()]++;
        }
        
        content.append("| Class | Count | Percentage |\n");
        content.append("|-------|-------|------------|\n");
        for (int i = 0; i < data.numClasses(); i++) {
            String className = data.classAttribute().value(i);
            double percentage = (classCounts[i] * 100.0) / data.numInstances();
            content.append(String.format("| %s | %d | %.1f%% |\n", className, classCounts[i], percentage));
        }
        content.append("\n");
        
        // Model Performance section (as expected by tests)
        content.append("## Model Performance\n\n");
        content.append("Multiple algorithms were evaluated using **10-fold cross-validation** with **Cost-Sensitive Classification** (10x penalty for False Negatives) and **SMOTE-based class balancing** for optimal minority class representation.\n\n");
        
        content.append("### Cross-Validation Performance Metrics\n\n");
        // New, improved table header
        content.append("| Algorithm | Recall@20%E | Popt | F1-Score (buggy) | Recall (buggy) | Precision (buggy) | Kappa |\n");
        content.append("|:---|---:|---:|---:|---:|---:|---:|\n");

        int buggyClassIndex = 1; // Default assumption

        for (Map.Entry<String, ModelEvaluationResult> entry : evaluationResults.entrySet()) {
            String algorithmName = entry.getKey();
            ModelEvaluationResult result = entry.getValue();
            Evaluation eval = result.getEvaluation();
            Map<String, Double> effortMetrics = result.getEffortAwareMetrics();

            // Determine the correct index for the 'buggy' class if not already done
            if (eval.getHeader() != null && eval.getHeader().classAttribute().value(0).equals("buggy")) {
                buggyClassIndex = 0;
            }

            double recallAt20 = effortMetrics.getOrDefault("Recall@20%Effort", 0.0);
            double popt = effortMetrics.getOrDefault("Popt", 0.0);

            try {
                content.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |\n",
                        algorithmName,
                        recallAt20,
                        popt,
                        eval.fMeasure(buggyClassIndex),
                        eval.recall(buggyClassIndex),
                        eval.precision(buggyClassIndex),
                        eval.kappa()
                ));
            } catch (Exception e) {
                // Fallback for cases where metrics cannot be calculated
                content.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |\n",
                        algorithmName,
                        recallAt20,
                        popt,
                        0.0, 0.0, 0.0, 0.0
                ));
            }
        }
        content.append("\n");
        
        // Algorithm sections with detailed performance analysis
        for (Map.Entry<String, ModelEvaluationResult> entry : evaluationResults.entrySet()) {
            String algorithm = entry.getKey();
            ModelEvaluationResult result = entry.getValue();
            Evaluation eval = result.getEvaluation();
            
            content.append("### ").append(algorithm).append("\n\n");
            
            // Get algorithm configuration description
            String algorithmConfig = getAlgorithmConfiguration(algorithm);
            content.append("**Algorithm Configuration:** ").append(algorithmConfig).append("\n\n");
            
            try {
                // Calculate metrics
                double accuracy = eval.pctCorrect() / 100.0;
                int algorithmBuggyClassIndex = data.classAttribute().value(1).equals("clean") ? 0 : 1;
                double precision = eval.precision(algorithmBuggyClassIndex);
                double recall = eval.recall(algorithmBuggyClassIndex);
                double fMeasure = eval.fMeasure(algorithmBuggyClassIndex);
                double auc = eval.areaUnderROC(algorithmBuggyClassIndex);
                double kappa = eval.kappa();
                
                // Handle NaN values
                if (Double.isNaN(precision)) precision = 0.0;
                if (Double.isNaN(recall)) recall = 0.0;
                if (Double.isNaN(fMeasure)) fMeasure = 0.0;
                if (Double.isNaN(auc)) auc = 0.0;
                if (Double.isNaN(kappa)) kappa = 0.0;
                
                // Performance highlights
                content.append("**Performance Highlights:**\n");
                content.append(String.format("- **Accuracy:** %.2f%% - %s\n", accuracy * 100, getAccuracyAssessment(accuracy)));
                content.append(String.format("- **Bug Detection Recall:** %.2f%% - %s\n", recall * 100, getRecallAssessment(recall)));
                content.append(String.format("- **Precision (Buggy):** %.2f%% - %s\n", precision * 100, getPrecisionAssessment(precision)));
                content.append(String.format("- **F1-Score:** %.2f%% - %s\n", fMeasure * 100, getF1Assessment(fMeasure)));
                content.append(String.format("- **AUC-ROC:** %.4f - %s\n", auc, getAUCAssessment(auc)));
                if (kappa > 0) {
                    content.append(String.format("- **Kappa:** %.4f - %s\n", kappa, getKappaAssessment(kappa)));
                }
                content.append("\n");
                
                // Strengths and weaknesses analysis
                content.append("**Strengths:** ").append(getAlgorithmStrengths(algorithm, accuracy, recall, precision, fMeasure)).append("\n\n");
                content.append("**Weaknesses:** ").append(getAlgorithmWeaknesses(algorithm, accuracy, recall, precision, fMeasure)).append("\n\n");
                
            } catch (Exception e) {
                content.append("Performance analysis unavailable due to evaluation metrics calculation error.\n\n");
            }
        }
        
        // Confusion matrices section
        content.append("### Confusion Matrices\n\n");
        content.append("Confusion matrices for all algorithms:\n\n");
        
        for (Map.Entry<String, ModelEvaluationResult> entry : evaluationResults.entrySet()) {
            content.append("#### ").append(entry.getKey()).append(" Confusion Matrix\n\n");
            content.append("```\n");
            try {
                content.append("=== Confusion Matrix ===\n\n");
                content.append("Predicted classes:\n");
                content.append(entry.getValue().getEvaluation().toMatrixString());
            } catch (Exception e) {
                content.append("Matrix not available");
            }
            content.append("```\n\n");
        }
        
        // Conclusion section (as expected by tests)  
        content.append("## Conclusion & Key Findings\n\n");
        content.append("### Analysis Summary\n\n");
        content.append("The analysis used **10-fold cross-validation with Cost-Sensitive Classification** (10x penalty for False Negatives) to ensure robust evaluation focused on bug detection.\n\n");
        
        // Find best performing model by Recall for buggy class (most important for bug detection)
        String bestModel = "N/A";
        double bestRecall = -1.0;
        double bestKappa = 0.0;
        double bestAccuracy = 0.0;
        
        for (Map.Entry<String, ModelEvaluationResult> entry : evaluationResults.entrySet()) {
            String algorithm = entry.getKey();
            ModelEvaluationResult result = entry.getValue();
            Evaluation eval = result.getEvaluation();
            double recall = 0.0;
            try {
                int bestModelBuggyClassIndex = data.classAttribute().value(1).equals("clean") ? 0 : 1;
                recall = eval.recall(bestModelBuggyClassIndex);
                if (Double.isNaN(recall)) recall = 0.0;
            } catch (Exception e) {
                recall = 0.0;
            }
            
            // Select model with highest recall for bug detection
            if (recall > bestRecall) {
                bestRecall = recall;
                bestModel = algorithm;
                bestAccuracy = eval.pctCorrect() / 100.0;
                bestKappa = eval.kappa();
                if (Double.isNaN(bestKappa)) bestKappa = 0.0;
            }
        }
        
        content.append("### Key Achievements\n\n");
        content.append("- Successfully analyzed ").append(data.numInstances()).append(" instances with Cost-Sensitive Classification\n");
        content.append("- Applied ").append(evaluationResults.size()).append(" machine learning algorithms with 10x False Negative penalty\n");
        content.append("- Achieved comprehensive bug prediction capabilities with enhanced recall\n\n");
        content.append("### Best Performing Model\n\n");
        if (!bestModel.equals("N/A")) {
            content.append(String.format("**%s** achieved the best bug detection performance:\n", bestModel));
            content.append(String.format("- **Bug Detection Recall:** %.4f (%.2f%%)\n", bestRecall, bestRecall * 100));
            content.append(String.format("- **Accuracy:** %.4f (%.2f%%)\n", bestAccuracy, bestAccuracy * 100));
            content.append(String.format("- **Kappa Statistic:** %.4f\n", bestKappa));
            content.append("\nThis model excels at detecting bugs with minimal false negatives.\n\n");
        } else {
            content.append("All models showed similar performance with cost-sensitive optimization.\n\n");
        }
        content.append("### Recommendations\n\n");
        content.append("1. **Focus on High-Recall Models:** Cost-sensitive training prioritizes bug detection\n");
        content.append("2. **Monitor False Negative Rate:** 10x penalty ensures fewer missed bugs\n");
        content.append("3. **Regular Model Retraining:** Update with new commit data for accuracy\n\n");
        content.append("### Technical Excellence\n\n");
        content.append("This analysis demonstrates **Cost-Sensitive Machine Learning** techniques optimized for bug prediction with enhanced recall performance.\n\n");
        content.append("---\n\n");
        content.append("**Report End** - Generated by JavaMLBugDetective Analysis Pipeline\n");
        
        // Write to file
        java.nio.file.Files.createDirectories(java.nio.file.Paths.get("./reports"));
        java.nio.file.Files.writeString(java.nio.file.Paths.get(reportFileName), content.toString());
        
        System.out.println("✓ Report generated successfully: " + reportFileName);
        return reportFileName;
    }

    /**
     * Generates HTML report by converting Markdown
     */
    public String generateHtmlReport() throws IOException {
        String markdownPath = generateMarkdownReport();
        String htmlPath = markdownPath.replace(".md", ".html");
        
        // Simple HTML conversion
        String content = java.nio.file.Files.readString(java.nio.file.Paths.get(markdownPath));
        String htmlContent = "<!DOCTYPE html><html><head><title>Report</title></head><body><pre>" + 
                           content + "</pre></body></html>";
        java.nio.file.Files.writeString(java.nio.file.Paths.get(htmlPath), htmlContent);
        
        System.out.println("✓ HTML report generated: " + htmlPath);
        return htmlPath;
    }

    /**
     * Simple Markdown to HTML converter
     */
    private void convertMarkdownToHtml(String markdownPath, String htmlPath) throws IOException {
        String content = java.nio.file.Files.readString(java.nio.file.Paths.get(markdownPath));
        
        // Basic HTML structure
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<title>JavaMLBugDetective Prediction Report</title>\n");
        html.append("<style>\n");
        html.append("body { font-family: Arial, sans-serif; margin: 40px; }\n");
        html.append("table { border-collapse: collapse; width: 100%; }\n");
        html.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        html.append("th { background-color: #f2f2f2; }\n");
        html.append("pre { background-color: #f4f4f4; padding: 10px; overflow-x: auto; }\n");
        html.append(".high-risk { background-color: #ffebee; }\n");
        html.append(".medium-risk { background-color: #fff3e0; }\n");
        html.append(".low-risk { background-color: #e8f5e8; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        
        // Convert Markdown to HTML (basic conversion)
        content = content.replaceAll("^# (.+)$", "<h1>$1</h1>")
                        .replaceAll("^## (.+)$", "<h2>$1</h2>")
                        .replaceAll("^### (.+)$", "<h3>$1</h3>")
                        .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                        .replaceAll("^---$", "<hr>")
                        .replaceAll("\\n\\n", "</p>\n<p>")
                        .replaceAll("^(.+)$", "<p>$1</p>");
        
        html.append(content);
        html.append("\n</body>\n</html>");
        
        java.nio.file.Files.writeString(java.nio.file.Paths.get(htmlPath), html.toString());
    }
    
    /**
     * Get algorithm configuration description
     */
    private String getAlgorithmConfiguration(String algorithm) {
        switch (algorithm) {
            case "NaiveBayes":
                return "Naive Bayes with Cost-Sensitive Classification (10x False Negative penalty) + Normalized + Balanced with SMOTE";
            case "SMO":
                return "SMO with Cost-Sensitive Classification (10x False Negative penalty) + Normalized + Balanced with SMOTE";
            case "RandomForest":
                return "RandomForest (100 trees) with Cost-Sensitive Classification (10x False Negative penalty) + Normalized + Balanced with SMOTE";
            case "J48":
                return "J48 Decision Tree with Cost-Sensitive Classification (10x False Negative penalty) + Normalized + Balanced with SMOTE";
            default:
                return algorithm + " with Cost-Sensitive Classification (10x False Negative penalty) + Normalized + Balanced with SMOTE";
        }
    }
    
    /**
     * Assess accuracy level
     */
    private String getAccuracyAssessment(double accuracy) {
        if (accuracy >= 0.8) return "Excellent overall correctness";
        if (accuracy >= 0.6) return "Good overall correctness";
        if (accuracy >= 0.5) return "Moderate overall correctness";
        if (accuracy >= 0.4) return "Lower overall accuracy but optimized for bug detection";
        return "Lower accuracy but may be suitable for high-recall scenarios";
    }
    
    /**
     * Assess recall level
     */
    private String getRecallAssessment(double recall) {
        if (recall >= 0.95) return "Exceptional bug detection (virtually no false negatives)";
        if (recall >= 0.9) return "Excellent at finding bugs (high sensitivity)";
        if (recall >= 0.8) return "Very good bug detection capability";
        if (recall >= 0.7) return "Good bug detection with moderate sensitivity";
        if (recall >= 0.5) return "Moderate bug detection capability";
        return "Limited bug detection capability";
    }
    
    /**
     * Assess precision level
     */
    private String getPrecisionAssessment(double precision) {
        if (precision >= 0.8) return "Excellent precision with minimal false positives";
        if (precision >= 0.6) return "Good precision with few false alarms";
        if (precision >= 0.5) return "Moderate precision with some false positives";
        if (precision >= 0.4) return "Moderate precision with trade-off for recall";
        return "Lower precision leading to more false alarms";
    }
    
    /**
     * Assess F1-score level
     */
    private String getF1Assessment(double f1) {
        if (f1 >= 0.8) return "Excellent precision-recall balance";
        if (f1 >= 0.7) return "Very good precision-recall balance";
        if (f1 >= 0.6) return "Good balance between precision and recall";
        if (f1 >= 0.5) return "Moderate precision-recall balance";
        return "Suboptimal precision-recall balance";
    }
    
    /**
     * Assess AUC-ROC level
     */
    private String getAUCAssessment(double auc) {
        if (auc >= 0.9) return "Outstanding discriminative ability";
        if (auc >= 0.8) return "Excellent discriminative performance";
        if (auc >= 0.7) return "Good discriminative ability";
        if (auc >= 0.6) return "Moderate discriminative performance";
        if (auc >= 0.5) return "Baseline discriminative performance";
        return "Poor discriminative ability";
    }
    
    /**
     * Assess Kappa statistic level
     */
    private String getKappaAssessment(double kappa) {
        if (kappa >= 0.8) return "Almost perfect agreement";
        if (kappa >= 0.6) return "Substantial agreement";
        if (kappa >= 0.4) return "Moderate agreement";
        if (kappa >= 0.2) return "Fair agreement beyond chance";
        if (kappa > 0) return "Slight agreement beyond chance";
        return "No agreement beyond chance";
    }
    
    /**
     * Generate algorithm-specific strengths
     */
    private String getAlgorithmStrengths(String algorithm, double accuracy, double recall, double precision, double fMeasure) {
        StringBuilder strengths = new StringBuilder();
        
        // Common strengths based on performance
        if (recall >= 0.9) {
            strengths.append("Excellent bug detection capability");
            if (recall >= 0.99) {
                strengths.append(" with virtually no missed bugs");
            }
            strengths.append(", making it suitable for safety-critical scenarios where missing bugs is costly");
        } else if (accuracy >= 0.6) {
            strengths.append("Good overall performance with balanced accuracy");
        }
        
        // Algorithm-specific strengths
        switch (algorithm) {
            case "RandomForest":
                if (strengths.length() > 0) strengths.append(". ");
                strengths.append("Provides robust predictions through ensemble learning with reduced overfitting risk");
                break;
            case "J48":
                if (strengths.length() > 0) strengths.append(". ");
                strengths.append("Interpretable decision rules for understanding bug patterns and feature importance");
                break;
            case "SMO":
                if (strengths.length() > 0) strengths.append(". ");
                strengths.append("Strong theoretical foundation with kernel-based classification suitable for complex patterns");
                break;
            case "NaiveBayes":
                if (strengths.length() > 0) strengths.append(". ");
                strengths.append("Fast training and prediction with good performance on limited data");
                break;
        }
        
        if (precision >= 0.5 && recall >= 0.8) {
            if (strengths.length() > 0) strengths.append(". ");
            strengths.append("Good balance between bug detection and false alarm minimization");
        }
        
        return strengths.toString();
    }
    
    /**
     * Generate algorithm-specific weaknesses
     */
    private String getAlgorithmWeaknesses(String algorithm, double accuracy, double recall, double precision, double fMeasure) {
        StringBuilder weaknesses = new StringBuilder();
        
        // Common weaknesses based on performance
        if (precision < 0.5) {
            weaknesses.append("Lower precision leads to more false alarms, requiring additional manual verification");
        } else if (accuracy < 0.5) {
            weaknesses.append("Lower overall accuracy may limit practical applicability");
        }
        
        // Algorithm-specific weaknesses
        switch (algorithm) {
            case "RandomForest":
                if (weaknesses.length() > 0) weaknesses.append(". ");
                weaknesses.append("More complex model requiring additional computational resources and memory");
                break;
            case "J48":
                if (weaknesses.length() > 0) weaknesses.append(". ");
                weaknesses.append("Single tree model may be less robust than ensemble methods for unseen data patterns");
                break;
            case "SMO":
                if (weaknesses.length() > 0) weaknesses.append(". ");
                weaknesses.append("Black-box model with limited interpretability for understanding decision factors");
                break;
            case "NaiveBayes":
                if (weaknesses.length() > 0) weaknesses.append(". ");
                weaknesses.append("Strong independence assumption may not hold for correlated software metrics");
                break;
        }
        
        if (recall < 0.7) {
            if (weaknesses.length() > 0) weaknesses.append(". ");
            weaknesses.append("May miss significant number of actual bugs in critical systems");
        }
        
        return weaknesses.toString();
    }
}
