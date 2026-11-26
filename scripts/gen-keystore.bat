@echo off
REM Owner: Member 5, Secure networking | Generates the TLS key pair and the client truststore.
REM
REM Usage:  scripts\gen-keystore.bat                 (localhost only)
REM         scripts\gen-keystore.bat 192.168.1.10    (also valid for the lecturer's LAN IP)
REM
REM Students on other laptops need a copy of certs\client-truststore.p12 (public certificate only).
cd /d "%~dp0.."
if not exist certs mkdir certs

REM SAN = Subject Alternative Name: host names / IPs this certificate is valid for.
set "SAN=dns:localhost,ip:127.0.0.1"
if not "%~1"=="" set "SAN=%SAN%,ip:%~1"

REM Remove old files, keytool refuses to overwrite an existing alias.
if exist certs\server.p12 del certs\server.p12
if exist certs\server.crt del certs\server.crt
if exist certs\client-truststore.p12 del certs\client-truststore.p12

REM 1) Self-signed RSA 2048-bit key pair in a PKCS12 keystore (server side, PRIVATE).
keytool -genkeypair -alias classpulse -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore certs\server.p12 -storepass changeit -keypass changeit -dname "CN=ClassPulse Server, OU=Network Programming, O=University, C=LK" -ext "SAN=%SAN%"
if errorlevel 1 exit /b 1

REM 2) Export only the PUBLIC certificate.
keytool -exportcert -alias classpulse -keystore certs\server.p12 -storepass changeit -rfc -file certs\server.crt
if errorlevel 1 exit /b 1

REM 3) Import that certificate into the client truststore ("the servers I trust").
keytool -importcert -noprompt -alias classpulse -file certs\server.crt -keystore certs\client-truststore.p12 -storetype PKCS12 -storepass changeit
if errorlevel 1 exit /b 1

echo Keystore ready: certs\server.p12 (server), certs\client-truststore.p12 (clients). SAN=%SAN%
