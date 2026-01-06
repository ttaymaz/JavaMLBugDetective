# Data Processing Scripts

This directory contains utility scripts for data format conversion, integrity verification, and validation data preparation.

## Scripts Overview

### 1. `convert_arff_to_csv.py`

Converts ARFF (Weka) format files to CSV format while preserving data integrity.

**Features:**
- Handles nominal, numeric, and string attributes
- Preserves missing values (? → empty string)
- Supports single file or batch conversion
- Provides conversion statistics

**Usage:**
```bash
# Single file conversion
python3 convert_arff_to_csv.py input.arff output.csv

# Batch conversion
python3 convert_arff_to_csv.py --batch arff_directory csv_directory
```

**Example:**
```bash
python3 convert_arff_to_csv.py \
  ../datasets/JML-BugDB-v1.0/arff/kafka.arff \
  ../datasets/JML-BugDB-v1.0/csv/kafka.csv
```

---

### 2. `verify_data_integrity.py`

Verifies data integrity between ARFF and CSV file pairs through cell-by-cell comparison.

**Verification Checks:**
- Row count consistency
- Column count consistency
- Cell-by-cell value matching
- Missing value alignment

**Usage:**
```bash
# Verify single file pair
python3 verify_data_integrity.py input.arff output.csv

# Batch verification with report
python3 verify_data_integrity.py --batch arff_dir csv_dir > INTEGRITY.log
```

**Example:**
```bash
python3 verify_data_integrity.py --batch \
  ../datasets/JML-BugDB-v1.0/arff \
  ../datasets/JML-BugDB-v1.0/csv
```

**Output:**
- ✓ PASSED: 100% accuracy, no data loss
- ✗ FAILED: Reports mismatches and statistics
- Certification statement for reproducibility

---

## Workflow: Regenerating the Dataset

The JavaMLBugDetective framework automatically generates ARFF files during analysis.
These scripts are used for post-processing:

```bash
# Step 1: Run JavaMLBugDetective analysis (generates ARFF automatically)
cd ..
./clean_and_run.sh

# Step 2: Convert ARFF to CSV
python3 convert_arff_to_csv.py --batch \
  ../datasets/JML-BugDB-v1.0/arff \
  ../datasets/JML-BugDB-v1.0/csv

# Step 3: Verify data integrity
python3 verify_data_integrity.py --batch \
  ../datasets/JML-BugDB-v1.0/arff \
  ../datasets/JML-BugDB-v1.0/csv \
  > ../datasets/JML-BugDB-v1.0/DATA_INTEGRITY.log
```

---

## Dependencies

All scripts use Python 3.x standard library only (no external dependencies):
- `csv` - CSV file processing
- `re` - Regular expressions (for ARFF parsing)
- `pathlib` - File path operations
- `datetime` - Timestamps for reports

**No pip install required!**

---

## Error Handling

All scripts include robust error handling:
- Missing files → Clear error messages
- Invalid formats → Parsing exceptions with context
- Data mismatches → Detailed mismatch reports

---

## Testing

To test the scripts on a small sample:

```bash
# Create test data
head -100 ../datasets/JML-BugDB-v1.0/arff/gson.arff > test_sample.arff

# Test conversion
python3 convert_arff_to_csv.py test_sample.arff test_sample.csv

# Test verification
python3 verify_data_integrity.py test_sample.arff test_sample.csv
```

---

## License

These scripts are released under the MIT License as part of the JavaMLBugDetective project.

---

**Maintained by**: JavaMLBugDetective Research Team  
**Last Updated**: December 2025
