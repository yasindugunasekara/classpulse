@echo off
REM Serves docs/sample-questions.csv at http://<this-pc>:8080/questions.csv
cd /d "%~dp0.."
if not exist out call scripts\build.bat

java -cp out com.classpulse.tools.QuestionWebServer %*
