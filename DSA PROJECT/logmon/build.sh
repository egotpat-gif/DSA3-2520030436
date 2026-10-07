#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
find src -name "*.java" > sources.txt
mkdir -p out/classes
javac -d out/classes @sources.txt
echo "Build OK -> out/classes"
