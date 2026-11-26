@echo off
REM Starts all 4 server services (NIO 6000, TLS 6001, UDP 6002, export 6003) in one JVM.
cd /d "%~dp0.."
if not exist out call scripts\build.bat
if not exist certs\server.p12 call scripts\gen-keystore.bat
java -cp out com.classpulse.server.ServerMain %*
