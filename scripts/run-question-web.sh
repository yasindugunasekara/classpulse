#!/usr/bin/env sh
# Serves members/member-3-udp-addressing-url/sample-questions.csv at http://<this-pc>:8080/questions.csv
cd "$(dirname "$0")/.." || exit 1
[ -d out ] || sh scripts/build.sh || exit 1

java -cp out com.classpulse.tools.QuestionWebServer "$@"
