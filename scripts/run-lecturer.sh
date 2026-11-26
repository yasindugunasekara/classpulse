#!/usr/bin/env sh
# Starts the Lecturer Dashboard (Swing). Optional argument: server host.
cd "$(dirname "$0")/.." || exit 1
[ -d out ] || sh scripts/build.sh || exit 1

java -cp out com.classpulse.client.LecturerMain "$@"
