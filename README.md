# JavaMLBugDetective

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.19218373.svg)](https://doi.org/10.5281/zenodo.19218373)
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
- **Version-Based Validation**: Uses Git tags for realistic, chronological evaluation
- **Hybrid Metrics**: Combines process, static, and diff/churn metrics
  - Process: NR, NDEV, AGE, EXP
  - Static (CK suite): WMC, CBO, RFC, LCOM, CYCLO
  - Diff/Churn: LINES_ADDED, LINES_DELETED, HUNK_COUNT
- **ML Pipeline**: RandomForest, J48, NaiveBayes, SMO (via Weka)
- **Class Balancing**: SMOTE and ClassBalancer
- **Cost-Sensitive Learning**: Configurable FN/FP cost matrix
- **Automated Reporting**: Scientific validation and prediction reports
- **Green AI**: 32,000x more energy-efficient than LLM-based approaches

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

*Note: Sequential boosting algorithms like AdaBoost collapse under the extreme SZZ label noise combined with SMOTE, while parallel ensembles (RandomForest) successfully isolate the true defect signal.*

### Cross-Project Validation Results

Cross-project validation results (Hybrid Model with Cost-Sensitive Learning):

| Project | F1-Score | Precision | Recall | Instances |
|---------|----------|-----------|--------|----------|
| Apache Kafka | 0.742 | 0.61 | 0.94 | 72,705 |
| Google Gson | 0.685 | 0.52 | 0.99 | 6,034 |
| Apache Commons-IO | 0.570 | 0.40 | 0.99 | 12,920 |

**Ablation Study Highlights:**
- Hybrid model outperforms static-only by up to **128%** (Commons-IO)
- Process metrics consistently outperform static metrics
- Model maintains robust performance despite 70.8% label noise

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

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.19218373.svg)](https://doi.org/10.5281/zenodo.19218373)

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
  doi       = {10.5281/zenodo.19218373},
  url       = {https://doi.org/10.5281/zenodo.19218373}
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

**Last Updated**: March 2026
