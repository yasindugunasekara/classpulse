@echo off
REM Starts the Student App (Swing). Optional argument: server host.
cd /d "%~dp0.."
if not exist out call scripts\build.bat

java -cp out com.classpulse.client.StudentMain %*
