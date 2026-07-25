#!/bin/bash
# Parallel driver for org.tymz.ml.SensitivityAnalysis (single-cell CLI mode).
# Fans out the 30 (mode,project,param) grid cells across processes since the
# underlying Weka RandomForest has no built-in multi-threading.
# Uses a portable polling-based concurrency limit (macOS ships bash 3.2,
# which lacks `wait -n`). Resumable: skips a cell if its output file already
# exists and is non-empty, so it's safe to re-run after a partial failure.
cd "$(dirname "$0")"

CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)"

PROJECTS="kafka gson commons-io"
COST_RATIOS="1.0 2.0 5.0 10.0 15.0 20.0"
SMOTE_KS="1 3 5 10"
MAX_PARALLEL=4

mkdir -p /tmp/sensitivity_out

run_cell() {
  mode=$1; project=$2; param=$3
  outfile="/tmp/sensitivity_out/${mode}_${project}_${param}.csv"
  if [ -s "$outfile" ]; then
    echo "[$(date +%H:%M:%S)] SKIP  $mode $project $param (already done: $(cat "$outfile"))"
    return
  fi
  echo "[$(date +%H:%M:%S)] START $mode $project $param"
  java -cp "$CP" org.tymz.ml.SensitivityAnalysis "$mode" "$project" "$param" > "$outfile" 2> "/tmp/sensitivity_out/${mode}_${project}_${param}.log"
  echo "[$(date +%H:%M:%S)] DONE  $mode $project $param -> $(cat "$outfile")"
}

wait_for_slot() {
  while [ "$(jobs -rp | wc -l | tr -d ' ')" -ge "$MAX_PARALLEL" ]; do
    sleep 3
  done
}

# Kafka cost-ratio cells are scheduled LAST: if an earlier invocation of this
# driver is still running in the background, this gives those in-flight jobs
# time to finish and get skipped by the resume check above instead of
# duplicated.
for p in gson commons-io; do
  for c in $COST_RATIOS; do
    wait_for_slot
    run_cell cost "$p" "$c" &
  done
  for k in $SMOTE_KS; do
    wait_for_slot
    run_cell smote "$p" "$k" &
  done
done
for k in $SMOTE_KS; do
  wait_for_slot
  run_cell smote kafka "$k" &
done
for c in $COST_RATIOS; do
  wait_for_slot
  run_cell cost kafka "$c" &
done
wait

# Assemble final CSVs
{
  echo "Project,CostRatio,Precision,Recall,F1,MCC"
  for p in $PROJECTS; do
    for c in $COST_RATIOS; do
      cat "/tmp/sensitivity_out/cost_${p}_${c}.csv"
    done
  done
} > sensitivity_cost_ratio.csv

{
  echo "Project,SmoteK,Precision,Recall,F1,MCC"
  for p in $PROJECTS; do
    for k in $SMOTE_KS; do
      cat "/tmp/sensitivity_out/smote_${p}_${k}.csv"
    done
  done
} > sensitivity_smote_k.csv

echo "Wrote sensitivity_cost_ratio.csv and sensitivity_smote_k.csv"
