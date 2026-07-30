# JavaMLBugDetective

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.18161122.svg)](https://doi.org/10.5281/zenodo.18161122)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Maven](https://img.shields.io/badge/Maven-3.9+-red)]()
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)]()

**JavaMLBugDetective** is a machine learning-aided bug prediction framework for Java projects. It combines static code analysis, process metrics, and evolutionary context modeling to predict defect-prone code.

Developed as part of Ph.D. research at Dokuz Eylül University, this framework is actively maintained and continues to evolve.

---

## 🚀 Quick Start

```bash
# Clone the repository
git clone https://github.com/ttaymaz/JavaMLBugDetective.git
cd JavaMLBugDetective

# Configure your target repository
cp sample.config.properties config.properties
# Edit config.properties with your settings

# Run the analysis pipeline
chmod +x clean_and_run.sh
./clean_and_run.sh
```

---

## ✨ Key Features

- **SZZ Algorithm**: Identifies bug-introducing commits via enhanced pattern matching
- **Version-Based Validation**: The framework supports Git-tag-based chronological evaluation as a configurable strategy (`ml.validation.strategy=version-based`); the accompanying paper's reported results use random 10-fold cross-validation instead, not this strategy — see the paper for the evaluation protocol actually used.
- **Hybrid Metrics**: Combines process, static, and diff/churn metrics
  - Process: NR, NDEV, AGE, EXP
  - Static (CK suite): WMC, TCC, RFC, LCOM, CBO, NCSS_CLASS, CYCLO_SUM
  - Diff/Churn: LINES_ADDED, LINES_DELETED, HUNK_COUNT
- **ML Pipeline**: RandomForest, J48, NaiveBayes, SMO (via Weka)
- **Class Balancing**: SMOTE and ClassBalancer
- **Cost-Sensitive Learning**: Configurable FN/FP cost matrix
- **Automated Reporting**: Scientific validation and prediction reports
- **Measured Efficiency**: The full three-project pipeline (SZZ labeling, feature extraction, training) runs in 30.6 minutes end-to-end, no GPU required

---

## 📁 Project Structure

```
JavaMLBugDetective/
├── src/main/java/org/tymz/
│   ├── config/        # Configuration management
│   ├── db/            # SQLite database operations
│   ├── feature/       # Data preprocessing
│   ├── git/           # JGit repository operations
│   ├── main/          # Application entry point
│   ├── metric/        # Metric calculators
│   ├── ml/            # Weka ML training
│   ├── report/        # Report generation
│   ├── szz/           # SZZ algorithm
│   └── version/       # Version management
├── src/test/          # Unit tests
├── pom.xml            # Maven configuration
├── config.properties  # Analysis settings
└── clean_and_run.sh   # Pipeline script
```

---

## ⚙️ Configuration

Edit `config.properties` to configure your analysis:

```properties
# Target repository
repository.url=https://github.com/your-org/your-project.git
repository.local.path=./repositories/your-project
project.name=your-project

# SZZ settings
szz.bug_fix_keywords=fix,bug,issue,defect,error,fault,problem,crash,exception

# ML settings
ml.algorithm=all
ml.balance.classes=true
ml.validation.strategy=version-based
ml.smote.enabled=true

# Cost-sensitive learning
ml.cost.fn=10.0  # False Negative cost
ml.cost.fp=1.0   # False Positive cost
```

### Private Repository Support

```properties
github.username=your-username
github.token=ghp_your_token_here
```

> **Note**: `config.properties` is excluded from Git via `.gitignore`

---

## 📊 Outputs

| Output | Description |
|--------|-------------|
| `[project]-dataset.arff` | ML dataset with all metrics |
| `reports/[project]-report-*.md` | Scientific validation report |
| `reports/[project]-prediction-*.md` | Bug prediction report |

---

## 📈 Verified Results

### Algorithm Robustness Benchmark (Gson Project)

Evaluation metrics comparing 5 distinct algorithms evaluating the 'buggy' target class. Models were evaluated using 10-fold Cross Validation, Cost-Sensitive Classification (10:1 FN:FP), and SMOTE class balancing.

| Algorithm      | Precision | Recall | F1-Score | MCC    |
|----------------|-----------|--------|----------|--------|
| **RandomForest** | **0.5111** | **0.9916** | **0.6745** | **0.3175** |
| J48            | 0.5341    | 0.8895 | 0.6675   | 0.2883 |
| NaiveBayes     | 0.4633    | 0.9179 | 0.6158   | 0.0672 |
| SMO            | 0.4518    | 0.9996 | 0.6223   | -0.0017|
| AdaBoostM1     | 0.4518    | 1.0000 | 0.6224   | NaN    |

*Note: This benchmark uses the framework's default configuration (SMOTE-based class balancing included), which is distinct from the CostSensitiveClassifier-only pipeline used for the accompanying paper's reported results (see the paper, Section 3.3.1, for why SMOTE was dropped from that pipeline). Within this SMOTE-containing configuration, sequential boosting algorithms (e.g., AdaBoost) proved more susceptible to degenerate predictions (NaN or near-zero MCC) than parallel ensembles (RandomForest).*

### Within-Project Validation Results

Evaluation is within-project 10-fold cross-validation (Hybrid Model with Cost-Sensitive Learning, mean over 30 independently seeded runs):

| Project | F1-Score | MCC |
|---------|----------|-----|
| Apache Kafka | 0.734 | 0.235 |
| Google Gson | 0.696 | 0.382 |
| Apache Commons-IO | 0.608 | 0.495 |

**Ablation Study Highlights:**
- Hybrid model outperforms static-only by up to **55%** (Commons-IO, F1)
- Process-based features provide a statistically robust advantage over static features **at the observed 50.5% label-noise level**; this advantage does not extend to further label degradation (see the accompanying paper's label-noise sensitivity analysis)

---

## 🔧 Requirements

- **Java**: JDK 21+
- **Maven**: 3.9+
- **Git**: For repository operations
- **RAM**: 4GB+ (recommended for large repos)

---

## 📦 Dependencies

- **Eclipse JGit**: Git operations
- **PMD**: Static code analysis
- **Weka**: Machine learning
- **SQLite JDBC**: Data persistence

---

## 📚 Dataset & Replication Package

The **JML-BugDB** dataset and complete replication package are permanently archived at Zenodo:

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.18161122.svg)](https://doi.org/10.5281/zenodo.18161122)

The package includes:
- JML-BugDB dataset (91,633 instances across 3 Java projects)
- Manual validation data and methodology
- Framework source code snapshot
- Replication instructions

---

## 📖 Citation

If you use this work in your research, please cite:

```bibtex
@software{taymaz2026jmlbugdetective,
  author    = {Taymaz, Turgay and Birant, Kökten Ulaş},
  title     = {JavaMLBugDetective: ML-Aided Bug Prediction Framework},
  year      = {2026},
  publisher = {Zenodo},
  doi       = {10.5281/zenodo.18161122},
  url       = {https://doi.org/10.5281/zenodo.18161122}
}
```

---

## 👥 Authors

**Turgay Taymaz** — Developer & Researcher  
**Assoc. Prof. Dr. Kökten Ulaş Birant** — Advisor

Dokuz Eylül University, The Graduate School of Natural and Applied Sciences

---

## 🤝 Contributing

Contributions are welcome! Please:
1. Open an issue for bugs or feature requests
2. Submit pull requests for improvements

Contact: turgay[at]taymaz.org

---

## 📄 License

This project is released under the [MIT License](LICENSE).

---

**Last Updated**: July 2026
