#!/bin/bash

# JavaMLBugDetective - Clean Run Script
# This script provides a complete clean execution of the entire analysis pipeline
# Author: JavaMLBugDetective Team
# Date: August 2025
# Updated: August 2, 2025 - Added HTML report support, ML algorithm diversity, reports directory handling

echo "🧹 JAVAMLBUGDETECTIVE - CLEAN RUN PIPELINE"
echo "=========================================="
echo ""

# Function to print step headers
print_step() {
    echo ""
    echo "🔄 STEP $1: $2"
    echo "----------------------------------------"
}

# Function to check command success
check_success() {
    if [ $? -eq 0 ]; then
        echo "✅ Success: $1"
    else
        echo "❌ Failed: $1"
        echo "Pipeline execution stopped due to error."
        exit 1
    fi
}

print_step "1" "WORKSPACE CLEANUP"

# Remove all generated report files
echo "🗑️  Removing all generated report files..."
rm -f *-report-*.md *-report-*.html
rm -f bug_prediction_report.md bug_prediction_report.html
rm -f cikti.txt cikti2.txt
if [ -d "reports" ]; then
    rm -rf reports/
    check_success "Reports directory cleanup"
else
    echo "ℹ️  No reports directory found (already clean)"
fi
check_success "Report files cleanup"

# Remove cloned repositories directory
echo "🗑️  Removing cloned repositories..."
if [ -d "repositories" ]; then
    rm -rf repositories/
    check_success "Repositories directory cleanup"
else
    echo "ℹ️  No repositories directory found (already clean)"
fi

# Remove SQLite database files
echo "🗑️  Removing SQLite database files..."
rm -f *.db *.db-shm *.db-wal
check_success "Database files cleanup"

# Remove ARFF dataset files
echo "🗑️  Removing ARFF dataset files..."
rm -f *.arff
check_success "ARFF files cleanup"

# Remove Maven target directory
echo "🗑️  Removing Maven target directory..."
if [ -d "target" ]; then
    rm -rf target/
    check_success "Maven target cleanup"
else
    echo "ℹ️  No target directory found (already clean)"
fi

# Remove any temporary files
echo "🗑️  Removing temporary files..."
rm -f *.tmp *.log *.cache
find . -name "*.tmp" -delete 2>/dev/null
find . -name ".DS_Store" -delete 2>/dev/null

echo ""
echo "✨ WORKSPACE CLEANED SUCCESSFULLY!"
echo "   - All report files removed (MD & HTML)"
echo "   - Reports directory removed"
echo "   - Repositories directory removed"  
echo "   - Database files removed"
echo "   - ARFF dataset files removed"
echo "   - Maven artifacts removed"
echo "   - Temporary files removed"

print_step "2" "DEPENDENCY VERIFICATION"

# Check if Maven is available
echo "🔍 Checking Maven installation..."
if command -v mvn &> /dev/null; then
    echo "✅ Maven found: $(mvn --version | head -1)"
else
    echo "❌ Maven not found! Please install Maven to continue."
    exit 1
fi

# Check if Java is available
echo "🔍 Checking Java installation..."
if command -v java &> /dev/null; then
    echo "✅ Java found: $(java -version 2>&1 | head -1)"
else
    echo "❌ Java not found! Please install Java to continue."
    exit 1
fi

# Check if Git is available
echo "🔍 Checking Git installation..."
if command -v git &> /dev/null; then
    echo "✅ Git found: $(git --version)"
else
    echo "❌ Git not found! Please install Git to continue."
    exit 1
fi

print_step "3" "FULL PIPELINE EXECUTION"

echo "🚀 Starting complete JavaMLBugDetective analysis pipeline..."
echo "   This will perform:"
echo "   - Repository cloning (Gson - 52 versions discovered)"
echo "   - Enhanced SZZ algorithm execution (169 bug-fixing + 734 bug-introducing commits)"
echo "   - Traditional + Novel diff/churn metrics calculation (LINES_ADDED, LINES_DELETED, HUNK_COUNT)"
echo "   - Process and static metrics calculation" 
echo "   - Version-based + Temporal validation strategies"
echo "   - Multi-algorithm ML training (NaiveBayes, J48, RandomForest, SMO)"
echo "   - Advanced class balancing (SMOTE/ClassBalancer)"
echo "   - Cross-validation evaluation with comprehensive metrics"
echo "   - Bug prediction on latest commit with confidence intervals"
echo "   - Enhanced report generation (scientific + practical)"
echo ""

# Record start time
start_time=$(date +%s)

# Execute the full pipeline
echo "⚡ Executing: mvn clean package exec:java"
echo ""

mvn clean package exec:java

# Check if the pipeline completed successfully
pipeline_exit_code=$?

# Record end time and calculate duration
end_time=$(date +%s)
duration=$((end_time - start_time))
minutes=$((duration / 60))
seconds=$((duration % 60))

echo ""
echo "⏱️  Pipeline execution time: ${minutes}m ${seconds}s"

if [ $pipeline_exit_code -eq 0 ]; then
    print_step "4" "EXECUTION SUMMARY"
    
    echo "🎉 PIPELINE COMPLETED SUCCESSFULLY!"
    echo ""
    echo "📊 Generated Artifacts:"
    
    # List generated reports (both MD and HTML)
    report_count_md=$(ls *-report-*.md 2>/dev/null | wc -l)
    report_count_html=$(ls *-report-*.html 2>/dev/null | wc -l)
    reports_dir_count=0
    
    if [ -d "reports" ]; then
        reports_dir_count=$(ls reports/*-report-*.* 2>/dev/null | wc -l)
        echo "   📋 Analysis Reports (reports/ directory: $reports_dir_count files):"
        if [ $reports_dir_count -gt 0 ]; then
            ls -la reports/*-report-*.* | awk '{print "      - " $9 " (" $5 " bytes)"}'
        fi
    fi
    
    if [ $report_count_md -gt 0 ] || [ $report_count_html -gt 0 ]; then
        total_reports=$((report_count_md + report_count_html))
        echo "   📋 Root Directory Reports ($total_reports files):"
        ls -la *-report-*.* 2>/dev/null | awk '{print "      - " $9 " (" $5 " bytes)"}'
    fi
    
    # Check database
    if [ -f "gson.db" ]; then
        db_size=$(ls -lh gson.db | awk '{print $5}')
        echo "   🗄️  Database: gson.db ($db_size)"
    fi
    
    # Check repositories
    if [ -d "repositories" ]; then
        repo_count=$(find repositories -name "*.java" | wc -l)
        echo "   📁 Cloned Repository: repositories/ ($repo_count Java files)"
    fi
    
    echo ""
    echo "🔬 Analysis Results Available In:"
    echo "   - Version-based validation reports with realistic evaluation methodology"
    echo "   - Enhanced bug prediction reports with confidence intervals"
    echo "   - Novel diff/churn metrics analysis and feature importance"
    echo "   - Multi-strategy validation comparison (temporal vs version-based)"
    echo "   - Advanced ML evaluations with class balancing results"
    echo "   - Comprehensive dataset with traditional + novel metrics"
    echo ""
    echo "✅ JAVAMLBUGDETECTIVE PIPELINE COMPLETE!"
    echo "   Ready for scientific analysis and bug prediction"
    
else
    echo ""
    echo "❌ PIPELINE EXECUTION FAILED!"
    echo "   Please check the error messages above for troubleshooting"
    echo "   Common issues:"
    echo "   - Network connectivity for repository cloning"
    echo "   - Memory allocation for large repositories"
    echo "   - Dependency resolution problems"
    exit $pipeline_exit_code
fi

echo ""
echo "🎯 To reproduce this analysis, simply run: ./clean_and_run.sh"
echo "=========================================="
