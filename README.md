# JavaMLBugDetective

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.18161123.svg)](https://doi.org/10.5281/zenodo.18161123)
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

| Metric | Kafka | Gson |
|--------|-------|------|
| Commits | 16,178 | 1,847 |
| Versions | 273 | 45 |
| F1-Score | 87.4% | - |
| Recall | 99.6% | - |
| Runtime | 48 min | 3 min |

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

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.18161123.svg)](https://doi.org/10.5281/zenodo.18161123)

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
  doi       = {10.5281/zenodo.18161123},
  url       = {https://doi.org/10.5281/zenodo.18161123}
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

**Last Updated**: January 2026
