@echo off
REM Automated end-to-end smoke test. Start the server (and run-question-web) first.
cd /d "%~dp0.."
if not exist out call scripts\build.bat
java -cp out com.classpulse.tests.SmokeTest %*
