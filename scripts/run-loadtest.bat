@echo off
REM Headless load test: N students login, join, attend and vote. Args: [host] [students] [waitSec]
cd /d "%~dp0.."
if not exist out call scripts\build.bat

java -cp out com.classpulse.tools.LoadTestClient %*
