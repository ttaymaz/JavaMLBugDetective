#!/usr/bin/env python3
"""
Data Integrity Verification Tool for JML-BugDB Dataset
=======================================================
This script verifies data integrity between ARFF and CSV file pairs by:
1. Comparing row counts
2. Comparing column counts
3. Verifying cell-by-cell data consistency
4. Checking for missing values alignment

The output serves as a certification that data conversion was performed
without loss or corruption.

Usage:
    python verify_data_integrity.py <arff_file> <csv_file>
    python verify_data_integrity.py --batch <arff_dir> <csv_dir>

Author: JavaMLBugDetective Research Team
License: MIT
"""

import sys
import csv
import re
from pathlib import Path
from typing import List, Tuple, Dict
from datetime import datetime


class IntegrityVerifier:
    """Verifies data integrity between ARFF and CSV file pairs."""
    
    def __init__(self):
        self.errors: List[str] = []
        self.warnings: List[str] = []
        self.stats: Dict = {}
    
    def parse_arff_data(self, arff_path: str) -> Tuple[List[str], List[List[str]]]:
        """Extract header and data from ARFF file."""
        attributes = []
        data = []
        in_data_section = False
        
        with open(arff_path, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                
                if not line or line.startswith('%'):
                    continue
                
                if line.upper().startswith('@ATTRIBUTE'):
                    attr_match = re.match(r'@ATTRIBUTE\s+(\S+)\s+(.+)', line, re.IGNORECASE)
                    if attr_match:
                        attr_name = attr_match.group(1).strip().strip("'\"")
                        attributes.append(attr_name)
                    continue
                
                if line.upper().startswith('@DATA'):
                    in_data_section = True
                    continue
                
                if in_data_section:
                    row = self._parse_arff_row(line, len(attributes))
                    if row:
                        data.append(row)
        
        return attributes, data
    
    def _parse_arff_row(self, line: str, expected_cols: int) -> List[str]:
        """Parse a single ARFF data row."""
        values = []
        current_value = ""
        in_quotes = False
        
        for char in line:
            if char in ("'", '"'):
                in_quotes = not in_quotes
            elif char == ',' and not in_quotes:
                values.append(current_value.strip().strip("'\""))
                current_value = ""
            else:
                current_value += char
        
        if current_value or len(values) < expected_cols:
            values.append(current_value.strip().strip("'\""))
        
        # Normalize missing values
        values = [v if v and v != '?' else '' for v in values]
        
        return values if len(values) == expected_cols else []
    
    def parse_csv_data(self, csv_path: str) -> Tuple[List[str], List[List[str]]]:
        """Extract header and data from CSV file."""
        with open(csv_path, 'r', encoding='utf-8') as f:
            reader = csv.reader(f)
            header = next(reader)
            data = [row for row in reader]
        
        return header, data
    
    def verify_pair(self, arff_path: str, csv_path: str) -> bool:
        """Verify integrity between an ARFF-CSV file pair."""
        self.errors = []
        self.warnings = []
        
        # Parse both files
        arff_header, arff_data = self.parse_arff_data(arff_path)
        csv_header, csv_data = self.parse_csv_data(csv_path)
        
        # Check header consistency
        if arff_header != csv_header:
            self.errors.append(f"Header mismatch: ARFF has {len(arff_header)} columns, CSV has {len(csv_header)}")
            if len(arff_header) == len(csv_header):
                for i, (a, c) in enumerate(zip(arff_header, csv_header)):
                    if a != c:
                        self.warnings.append(f"Column {i}: ARFF='{a}' vs CSV='{c}'")
        
        # Check row count
        if len(arff_data) != len(csv_data):
            self.errors.append(f"Row count mismatch: ARFF has {len(arff_data)} rows, CSV has {len(csv_data)}")
        
        # Check cell-by-cell consistency
        mismatches = 0
        checked_cells = 0
        max_mismatches_to_report = 10
        
        for i, (arff_row, csv_row) in enumerate(zip(arff_data, csv_data)):
            if len(arff_row) != len(csv_row):
                self.errors.append(f"Row {i+1}: Column count mismatch")
                continue
            
            for j, (arff_val, csv_val) in enumerate(zip(arff_row, csv_row)):
                checked_cells += 1
                if arff_val != csv_val:
                    mismatches += 1
                    if mismatches <= max_mismatches_to_report:
                        self.warnings.append(
                            f"Row {i+1}, Col {j+1} ('{arff_header[j] if j < len(arff_header) else 'unknown'}'): "
                            f"ARFF='{arff_val}' vs CSV='{csv_val}'"
                        )
        
        if mismatches > max_mismatches_to_report:
            self.warnings.append(f"... and {mismatches - max_mismatches_to_report} more cell mismatches")
        
        # Store statistics
        self.stats = {
            'arff_rows': len(arff_data),
            'csv_rows': len(csv_data),
            'arff_cols': len(arff_header),
            'csv_cols': len(csv_header),
            'checked_cells': checked_cells,
            'mismatched_cells': mismatches,
            'accuracy': (checked_cells - mismatches) / checked_cells * 100 if checked_cells > 0 else 0
        }
        
        return len(self.errors) == 0 and mismatches == 0
    
    def print_report(self, arff_name: str, csv_name: str, passed: bool) -> None:
        """Print verification report."""
        status = "✓ PASSED" if passed else "✗ FAILED"
        print(f"\n{'=' * 70}")
        print(f"INTEGRITY CHECK: {arff_name} <-> {csv_name}")
        print(f"{'=' * 70}")
        print(f"Status: {status}")
        print(f"\nStatistics:")
        print(f"  ARFF: {self.stats['arff_rows']:,} rows × {self.stats['arff_cols']} columns")
        print(f"  CSV:  {self.stats['csv_rows']:,} rows × {self.stats['csv_cols']} columns")
        print(f"  Cells checked: {self.stats['checked_cells']:,}")
        print(f"  Mismatched cells: {self.stats['mismatched_cells']}")
        print(f"  Accuracy: {self.stats['accuracy']:.4f}%")
        
        if self.errors:
            print(f"\nErrors ({len(self.errors)}):")
            for error in self.errors:
                print(f"  ✗ {error}")
        
        if self.warnings:
            print(f"\nWarnings ({len(self.warnings)}):")
            for warning in self.warnings[:10]:  # Limit to 10 warnings
                print(f"  ⚠ {warning}")
            if len(self.warnings) > 10:
                print(f"  ... and {len(self.warnings) - 10} more warnings")
        
        print(f"{'=' * 70}")


def verify_single_pair(arff_path: str, csv_path: str) -> bool:
    """Verify a single ARFF-CSV pair."""
    verifier = IntegrityVerifier()
    passed = verifier.verify_pair(arff_path, csv_path)
    verifier.print_report(Path(arff_path).name, Path(csv_path).name, passed)
    return passed


def batch_verify(arff_dir: str, csv_dir: str) -> None:
    """Verify all ARFF-CSV pairs in directories."""
    arff_path = Path(arff_dir)
    csv_path = Path(csv_dir)
    
    arff_files = sorted(arff_path.glob('*.arff'))
    
    if not arff_files:
        print(f"No ARFF files found in {arff_dir}")
        return
    
    print("=" * 70)
    print(f"JML-BugDB DATA INTEGRITY VERIFICATION REPORT")
    print(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 70)
    print(f"\nVerifying {len(arff_files)} file pair(s)...")
    
    results = []
    
    for arff_file in arff_files:
        csv_file = csv_path / f"{arff_file.stem}.csv"
        
        if not csv_file.exists():
            print(f"\n✗ SKIPPED: {arff_file.name} (CSV counterpart not found)")
            results.append((arff_file.name, False))
            continue
        
        verifier = IntegrityVerifier()
        passed = verifier.verify_pair(str(arff_file), str(csv_file))
        verifier.print_report(arff_file.name, csv_file.name, passed)
        results.append((arff_file.name, passed))
    
    # Summary
    print("\n" + "=" * 70)
    print("VERIFICATION SUMMARY")
    print("=" * 70)
    passed_count = sum(1 for _, passed in results if passed)
    failed_count = len(results) - passed_count
    
    print(f"Total files verified: {len(results)}")
    print(f"Passed: {passed_count}")
    print(f"Failed: {failed_count}")
    print(f"\nOverall Status: {'✓ ALL CHECKS PASSED' if failed_count == 0 else '✗ SOME CHECKS FAILED'}")
    print("=" * 70)
    
    if failed_count == 0:
        print("\n✓ CERTIFICATION: All data conversions verified successfully.")
        print("  No data loss or corruption detected during ARFF → CSV conversion.")
        print("  This dataset is ready for reproducible research.")


def main():
    """Main entry point."""
    if len(sys.argv) < 3:
        print("Usage:")
        print("  Single pair: python verify_data_integrity.py <input.arff> <output.csv>")
        print("  Batch mode:  python verify_data_integrity.py --batch <arff_dir> <csv_dir>")
        sys.exit(1)
    
    if sys.argv[1] == '--batch':
        if len(sys.argv) != 4:
            print("Error: Batch mode requires ARFF and CSV directories")
            sys.exit(1)
        batch_verify(sys.argv[2], sys.argv[3])
    else:
        if len(sys.argv) != 3:
            print("Error: Single pair mode requires ARFF and CSV file paths")
            sys.exit(1)
        passed = verify_single_pair(sys.argv[1], sys.argv[2])
        sys.exit(0 if passed else 1)


if __name__ == '__main__':
    main()
