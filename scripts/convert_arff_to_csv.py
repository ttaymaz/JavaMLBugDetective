#!/usr/bin/env python3
"""
ARFF to CSV Converter for JML-BugDB Dataset
============================================
This script converts ARFF (Weka) format files to CSV format while preserving
data integrity. It handles nominal attributes, numeric attributes, and string
attributes appropriately.

Usage:
    python convert_arff_to_csv.py <input.arff> <output.csv>
    python convert_arff_to_csv.py --batch <arff_dir> <csv_dir>

Author: JavaMLBugDetective Research Team
License: MIT
"""

import sys
import os
import re
import csv
from pathlib import Path
from typing import List, Tuple, Dict


class ARFFConverter:
    """Converts ARFF files to CSV format with data integrity preservation."""
    
    def __init__(self):
        self.attributes: List[Tuple[str, str]] = []
        self.data: List[List[str]] = []
        self.relation_name: str = ""
    
    def parse_arff(self, arff_path: str) -> None:
        """Parse ARFF file and extract metadata and data."""
        with open(arff_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        
        in_data_section = False
        
        for line in lines:
            line = line.strip()
            
            # Skip empty lines and comments
            if not line or line.startswith('%'):
                continue
            
            # Parse @RELATION
            if line.upper().startswith('@RELATION'):
                self.relation_name = line.split(maxsplit=1)[1].strip().strip("'\"")
                continue
            
            # Parse @ATTRIBUTE
            if line.upper().startswith('@ATTRIBUTE'):
                attr_match = re.match(r'@ATTRIBUTE\s+(\S+)\s+(.+)', line, re.IGNORECASE)
                if attr_match:
                    attr_name = attr_match.group(1).strip().strip("'\"")
                    attr_type = attr_match.group(2).strip()
                    self.attributes.append((attr_name, attr_type))
                continue
            
            # Start of data section
            if line.upper().startswith('@DATA'):
                in_data_section = True
                continue
            
            # Parse data rows
            if in_data_section:
                # Handle CSV-style data in ARFF
                row = self._parse_data_row(line)
                if row:
                    self.data.append(row)
    
    def _parse_data_row(self, line: str) -> List[str]:
        """Parse a single data row from ARFF format."""
        # Handle quoted strings and commas within strings
        values = []
        current_value = ""
        in_quotes = False
        
        for char in line:
            if char == "'" or char == '"':
                in_quotes = not in_quotes
            elif char == ',' and not in_quotes:
                values.append(current_value.strip().strip("'\""))
                current_value = ""
            else:
                current_value += char
        
        # Add last value
        if current_value or len(values) < len(self.attributes):
            values.append(current_value.strip().strip("'\""))
        
        # Handle missing values
        values = [v if v and v != '?' else '' for v in values]
        
        return values if len(values) == len(self.attributes) else []
    
    def write_csv(self, csv_path: str) -> None:
        """Write parsed data to CSV file."""
        with open(csv_path, 'w', newline='', encoding='utf-8') as f:
            writer = csv.writer(f)
            
            # Write header
            header = [attr[0] for attr in self.attributes]
            writer.writerow(header)
            
            # Write data rows
            writer.writerows(self.data)
    
    def get_stats(self) -> Dict[str, int]:
        """Return statistics about the converted data."""
        return {
            'relation': self.relation_name,
            'attributes': len(self.attributes),
            'instances': len(self.data),
            'cells': len(self.attributes) * len(self.data) if self.data else 0
        }


def convert_single_file(arff_path: str, csv_path: str) -> Dict[str, int]:
    """Convert a single ARFF file to CSV."""
    print(f"Converting {arff_path} -> {csv_path}")
    
    converter = ARFFConverter()
    converter.parse_arff(arff_path)
    converter.write_csv(csv_path)
    
    stats = converter.get_stats()
    print(f"  ✓ Relation: {stats['relation']}")
    print(f"  ✓ Attributes: {stats['attributes']}")
    print(f"  ✓ Instances: {stats['instances']}")
    print(f"  ✓ Total cells: {stats['cells']}")
    
    return stats


def batch_convert(arff_dir: str, csv_dir: str) -> None:
    """Convert all ARFF files in a directory to CSV."""
    arff_path = Path(arff_dir)
    csv_path = Path(csv_dir)
    
    # Create output directory if it doesn't exist
    csv_path.mkdir(parents=True, exist_ok=True)
    
    arff_files = list(arff_path.glob('*.arff'))
    
    if not arff_files:
        print(f"No ARFF files found in {arff_dir}")
        return
    
    print(f"Found {len(arff_files)} ARFF file(s) to convert\n")
    
    total_stats = {'instances': 0, 'attributes': 0, 'cells': 0}
    
    for arff_file in sorted(arff_files):
        csv_file = csv_path / f"{arff_file.stem}.csv"
        stats = convert_single_file(str(arff_file), str(csv_file))
        total_stats['instances'] += stats['instances']
        total_stats['cells'] += stats['cells']
        print()
    
    print("=" * 60)
    print("BATCH CONVERSION SUMMARY")
    print("=" * 60)
    print(f"Total files converted: {len(arff_files)}")
    print(f"Total instances: {total_stats['instances']:,}")
    print(f"Total data cells: {total_stats['cells']:,}")
    print("=" * 60)


def main():
    """Main entry point for the script."""
    if len(sys.argv) < 3:
        print("Usage:")
        print("  Single file: python convert_arff_to_csv.py <input.arff> <output.csv>")
        print("  Batch mode:  python convert_arff_to_csv.py --batch <arff_dir> <csv_dir>")
        sys.exit(1)
    
    if sys.argv[1] == '--batch':
        if len(sys.argv) != 4:
            print("Error: Batch mode requires input and output directories")
            sys.exit(1)
        batch_convert(sys.argv[2], sys.argv[3])
    else:
        if len(sys.argv) != 3:
            print("Error: Single file mode requires input and output file paths")
            sys.exit(1)
        convert_single_file(sys.argv[1], sys.argv[2])


if __name__ == '__main__':
    main()
