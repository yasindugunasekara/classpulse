#!/usr/bin/env sh
# Automated end-to-end smoke test. Start the server (and run-question-web) first.
cd "$(dirname "$0")/.." || exit 1
[ -d out ] || sh scripts/build.sh || exit 1
java -cp out com.classpulse.tests.SmokeTest "$@"
