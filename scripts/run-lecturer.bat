@echo off
REM Starts the Lecturer Dashboard (Swing). Optional argument: server host.
cd /d "%~dp0.."
if not exist out call scripts\build.bat

java -cp out com.classpulse.client.LecturerMain %*
