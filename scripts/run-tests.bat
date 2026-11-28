@echo off
REM Runs every unit test (no server needed). Stops at the first failing test class.
cd /d "%~dp0.."
call scripts\build.bat || exit /b 1
if not exist certs\server.p12 call scripts\gen-keystore.bat
for %%T in (SecurityTest EngineConcurrencyTest ClientTest AddressingUrlTest ExportCsvTest) do (
  echo ===== %%T
  java -cp out com.classpulse.tests.%%T || (echo FAILED: %%T & exit /b 1)
)
echo All unit tests passed. For the end-to-end test start the server and run scripts\run-smoketest.bat
