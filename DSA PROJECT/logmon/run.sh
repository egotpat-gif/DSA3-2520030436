#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
./build.sh
java -cp out/classes logmon.Main "${1:-data/sample_server.log}"
