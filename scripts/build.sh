#!/usr/bin/env sh
# Compiles every member folder (members/*) plus tests/ into out/ with plain javac (no Maven/Gradle).
cd "$(dirname "$0")/.." || exit 1
rm -rf out
mkdir -p out
# Collect all .java files with paths relative to the project root (no spaces in these paths).
find members tests -name "*.java" > out/sources.txt
# --release 17 makes sure we only use Java 17 APIs even on a newer JDK.
javac --release 17 -encoding UTF-8 -d out @out/sources.txt || exit 1
echo "Build OK -> out/"
