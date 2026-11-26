#!/usr/bin/env sh
# Headless load test: N students login, join, attend and vote. Args: [host] [students] [waitSec]
cd "$(dirname "$0")/.." || exit 1
[ -d out ] || sh scripts/build.sh || exit 1

java -cp out com.classpulse.tools.LoadTestClient "$@"
