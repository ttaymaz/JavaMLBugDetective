#!/bin/bash
# Parallel driver for org.tymz.main.AgeAblationExperiment (single-cell CLI mode).
# Usage: ./run_age_ablation_parallel.sh <project1> [project2] [project3]
cd "$(dirname "$0")"

CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)"

PROJECTS="$@"
if [ -z "$PROJECTS" ]; then
  echo "Usage: $0 <project> [project...]"
  exit 1
fi

VARIANTS="process hybrid"
SEEDS=$(seq 1 30)
MAX_PARALLEL=6

OUT_DIR=/tmp/age_ablation_out
mkdir -p "$OUT_DIR"

run_cell() {
  project=$1; variant=$2; seed=$3
  outfile="$OUT_DIR/${project}_${variant}_${seed}.csv"
  if [ -s "$outfile" ]; then
    return
  fi
  java -cp "$CP" org.tymz.main.AgeAblationExperiment "$project" "$variant" "$seed" > "$outfile" 2> "$OUT_DIR/${project}_${variant}_${seed}.log"
  echo "[$(date +%H:%M:%S)] DONE $project $variant $seed -> $(cat "$outfile")"
}

wait_for_slot() {
  while [ "$(jobs -rp | wc -l | tr -d ' ')" -ge "$MAX_PARALLEL" ]; do
    sleep 2
  done
}

start_ts=$(date +%s)
for p in $PROJECTS; do
  for v in $VARIANTS; do
    for s in $SEEDS; do
      wait_for_slot
      run_cell "$p" "$v" "$s" &
    done
  done
done
wait
end_ts=$(date +%s)
echo "Total wall time: $((end_ts - start_ts)) seconds"

{
  echo "Project,Variant,Seed,TP,FP,TN,FN,Precision,Recall,F1,MCC"
  for p in $PROJECTS; do
    for v in $VARIANTS; do
      for s in $SEEDS; do
        f="$OUT_DIR/${p}_${v}_${s}.csv"
        [ -s "$f" ] && cat "$f"
      done
    done
  done
} > age_ablation_raw_regenerated.csv

echo "Wrote age_ablation_raw_regenerated.csv ($(wc -l < age_ablation_raw_regenerated.csv) lines)"
