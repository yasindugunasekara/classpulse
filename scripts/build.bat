@echo off
REM Compiles every member folder (members\*) plus tests\ into out\ with plain javac.
setlocal enabledelayedexpansion
cd /d "%~dp0.."
if exist out rmdir /s /q out
mkdir out
REM Write every .java path in quotes with forward slashes (safe for folders with spaces).
type nul > out\sources.txt
for /r members %%f in (*.java) do (
  set "p=%%f"
  set "p=!p:\=/!"
  echo "!p!">> out\sources.txt
)
for /r tests %%f in (*.java) do (
  set "p=%%f"
  set "p=!p:\=/!"
  echo "!p!">> out\sources.txt
)
javac --release 17 -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
echo Build OK -^> out\
