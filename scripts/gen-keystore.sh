#!/usr/bin/env sh
# Owner: Member 5, Secure networking | Generates the TLS key pair and the client truststore.
#
# Usage:  sh scripts/gen-keystore.sh                 (localhost only)
#         sh scripts/gen-keystore.sh 192.168.1.10    (also valid for the lecturer's LAN IP)
#
# Students on other laptops need a copy of certs/client-truststore.p12 (it holds only the
# PUBLIC certificate, never the private key, so it is safe to share).
cd "$(dirname "$0")/.." || exit 1
mkdir -p certs

# SAN = Subject Alternative Name: the host names / IPs this certificate is valid for.
# The client checks it (hostname verification), so the lecturer's LAN IP must be listed here.
SAN="dns:localhost,ip:127.0.0.1"
if [ -n "$1" ]; then
  SAN="$SAN,ip:$1"
fi

# Remove old files, keytool refuses to overwrite an existing alias.
rm -f certs/server.p12 certs/server.crt certs/client-truststore.p12

# 1) Create a self-signed RSA 2048-bit key pair inside a PKCS12 keystore (server side, PRIVATE).
keytool -genkeypair -alias classpulse -keyalg RSA -keysize 2048 -validity 365 \
  -storetype PKCS12 -keystore certs/server.p12 -storepass changeit -keypass changeit \
  -dname "CN=ClassPulse Server, OU=Network Programming, O=University, C=LK" \
  -ext "SAN=$SAN" || exit 1

# 2) Export only the PUBLIC certificate from the keystore into a .crt file.
keytool -exportcert -alias classpulse -keystore certs/server.p12 -storepass changeit \
  -rfc -file certs/server.crt || exit 1

# 3) Import that certificate into the client truststore = "the list of servers I trust".
keytool -importcert -noprompt -alias classpulse -file certs/server.crt \
  -keystore certs/client-truststore.p12 -storetype PKCS12 -storepass changeit || exit 1

echo "Keystore ready: certs/server.p12 (server), certs/client-truststore.p12 (clients). SAN=$SAN"
