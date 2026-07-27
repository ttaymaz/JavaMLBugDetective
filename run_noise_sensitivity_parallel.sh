#!/bin/bash
# Parallel driver for org.tymz.main.NoiseSensitivityExperiment (single-cell CLI mode).
# Runs only NoiseLevel > 0 cells; NoiseLevel = 0.0 is reused from
# significance_raw_results.csv (see noise_sensitivity_raw.csv's own header).
# Usage: ./run_noise_sensitivity_parallel.sh <project1> [project2] [project3]
cd "$(dirname "$0")"

CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)"

PROJECTS="$@"
if [ -z "$PROJECTS" ]; then
  echo "Usage: $0 <project> [project...]"
  exit 1
fi

FEATURE_SETS="hybrid process static"
LEVELS="0.1 0.2 0.3 0.4 0.5"
SEEDS=$(seq 1 10)
MAX_PARALLEL=6

OUT_DIR=/tmp/noise_sensitivity_out
mkdir -p "$OUT_DIR"

run_cell() {
  project=$1; fs=$2; level=$3; seed=$4
  outfile="$OUT_DIR/${project}_${fs}_${level}_${seed}.csv"
  if [ -s "$outfile" ]; then
    return
  fi
  java -cp "$CP" org.tymz.main.NoiseSensitivityExperiment "$project" "$fs" "$level" "$seed" > "$outfile" 2> "$OUT_DIR/${project}_${fs}_${level}_${seed}.log"
  echo "[$(date +%H:%M:%S)] DONE $project $fs $level $seed -> $(cat "$outfile")"
}

wait_for_slot() {
  while [ "$(jobs -rp | wc -l | tr -d ' ')" -ge "$MAX_PARALLEL" ]; do
    sleep 2
  done
}

start_ts=$(date +%s)
for p in $PROJECTS; do
  for fs in $FEATURE_SETS; do
    for l in $LEVELS; do
      for s in $SEEDS; do
        wait_for_slot
        run_cell "$p" "$fs" "$l" "$s" &
      done
    done
  done
done
wait
end_ts=$(date +%s)
echo "Total wall time: $((end_ts - start_ts)) seconds"

{
  for p in $PROJECTS; do
    for fs in $FEATURE_SETS; do
      for l in $LEVELS; do
        for s in $SEEDS; do
          f="$OUT_DIR/${p}_${fs}_${l}_${s}.csv"
          [ -s "$f" ] && cat "$f"
        done
      done
    done
  done
} > noise_sensitivity_gt0_regenerated.csv

echo "Wrote noise_sensitivity_gt0_regenerated.csv ($(wc -l < noise_sensitivity_gt0_regenerated.csv) lines)"
