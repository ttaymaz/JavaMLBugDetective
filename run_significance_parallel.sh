#!/bin/bash
# Parallel driver for org.tymz.main.SignificanceExperiment (single-cell CLI mode).
# Usage: ./run_significance_parallel.sh <project1> [project2] [project3]
#   e.g. ./run_significance_parallel.sh gson          (pilot)
#        ./run_significance_parallel.sh kafka gson commons-io   (full run)
cd "$(dirname "$0")"

CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)"

PROJECTS="$@"
if [ -z "$PROJECTS" ]; then
  echo "Usage: $0 <project> [project...]"
  exit 1
fi

FEATURE_SETS="hybrid process static"
SEEDS=$(seq 1 30)
MAX_PARALLEL=6

OUT_DIR=/tmp/significance_out
mkdir -p "$OUT_DIR"

run_cell() {
  project=$1; fs=$2; seed=$3
  outfile="$OUT_DIR/${project}_${fs}_${seed}.csv"
  if [ -s "$outfile" ]; then
    return
  fi
  java -cp "$CP" org.tymz.main.SignificanceExperiment "$project" "$fs" "$seed" > "$outfile" 2> "$OUT_DIR/${project}_${fs}_${seed}.log"
  echo "[$(date +%H:%M:%S)] DONE $project $fs $seed -> $(cat "$outfile")"
}

wait_for_slot() {
  while [ "$(jobs -rp | wc -l | tr -d ' ')" -ge "$MAX_PARALLEL" ]; do
    sleep 2
  done
}

start_ts=$(date +%s)
for p in $PROJECTS; do
  for fs in $FEATURE_SETS; do
    for s in $SEEDS; do
      wait_for_slot
      run_cell "$p" "$fs" "$s" &
    done
  done
done
wait
end_ts=$(date +%s)
echo "Total wall time: $((end_ts - start_ts)) seconds"

# Assemble combined CSV
{
  echo "Project,FeatureSet,Seed,Precision,Recall,F1,MCC"
  for p in $PROJECTS; do
    for fs in $FEATURE_SETS; do
      for s in $SEEDS; do
        f="$OUT_DIR/${p}_${fs}_${s}.csv"
        [ -s "$f" ] && cat "$f"
      done
    done
  done
} > significance_raw_results.csv

echo "Wrote significance_raw_results.csv ($(wc -l < significance_raw_results.csv) lines)"
