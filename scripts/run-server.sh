#!/usr/bin/env sh
# Starts all 4 server services (NIO 6000, TLS 6001, UDP 6002, export 6003) in one JVM.
cd "$(dirname "$0")/.." || exit 1
[ -d out ] || sh scripts/build.sh || exit 1
[ -f certs/server.p12 ] || sh scripts/gen-keystore.sh || exit 1
java -cp out com.classpulse.server.ServerMain "$@"
