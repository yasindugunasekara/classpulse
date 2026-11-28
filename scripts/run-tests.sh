#!/usr/bin/env sh
# Runs every unit test (no server needed). Stops at the first failing test class.
cd "$(dirname "$0")/.." || exit 1
sh scripts/build.sh || exit 1
[ -f certs/server.p12 ] || sh scripts/gen-keystore.sh || exit 1
for t in SecurityTest EngineConcurrencyTest ClientTest AddressingUrlTest ExportCsvTest; do
  echo "===== $t"
  java -cp out com.classpulse.tests.$t || { echo "FAILED: $t"; exit 1; }
done
echo "All unit tests passed. For the end-to-end test start the server and run scripts/run-smoketest.sh"
