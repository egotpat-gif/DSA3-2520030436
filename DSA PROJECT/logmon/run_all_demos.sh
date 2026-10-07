#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
./build.sh
for f in sample_server test2_critical_burst test3_clean_server test4_unknown_errors; do
  echo
  echo "################################################################"
  echo "# DEMO: data/$f.log"
  echo "################################################################"
  java -cp out/classes logmon.Main "data/$f.log"
done
