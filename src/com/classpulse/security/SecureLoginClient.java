// Owner: Member 5, Secure networking (SSL/TLS client) | Lecture topic: SSLSocket, truststore, handshake
package com.classpulse.security;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Client side of the TLS login. Used by both GUIs and by the load test.
 *
 * The TRUSTSTORE contains the server's public certificate. During the handshake the server
 * presents its certificate; the TrustManager checks that it is one we trust. If someone on the
 * Wi-Fi pretends to be the server, their certificate is not in our truststore and the
 * handshake fails, before we ever send the password.
 */
public class SecureLoginClient {

    /** Result of a successful login. */
    public record LoginResult(String token, String role) { }

    /** A login failure with a message that is friendly enough to show in the GUI. */
    public static class LoginException extends Exception {
        private static final long serialVersionUID = 1L;

        public LoginException(String message) {
            super(message);
        }
    }

    private final Config config;

    public SecureLoginClient(Config config) {
        this.config = config;
    }

    public LoginResult login(String host, String username, String password) throws LoginException {
        return send(host, Protocol.join(Protocol.LOGIN, username, password));
    }

    public LoginResult register(String host, String username, String password) throws LoginException {
        return send(host, Protocol.join(Protocol.REGISTER, username, password));
    }

    private SSLContext createClientContext() throws Exception {
        Path tsPath = Paths.get(config.get("tls.truststore", "certs/client-truststore.p12"));
        if (!Files.exists(tsPath)) {
            throw new LoginException("Truststore " + tsPath + " not found. Copy certs/client-truststore.p12 "
                    + "from the lecturer's laptop (or run scripts/gen-keystore).");
        }
        char[] password = config.get("tls.truststore.password", "changeit").toCharArray();
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(tsPath)) {
            trustStore.load(in, password);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null); // we trust exactly the certificates in the truststore
        return ctx;
    }

    private LoginResult send(String host, String requestLine) throws LoginException {
        SSLContext ctx;
        try {
            ctx = createClientContext();
        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("Cannot load truststore: " + e.getMessage());
        }
        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket()) {
            if (config.getBool("tls.verifyHostname", true)) {
                // Check that the certificate's SAN really names the host we connect to.
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
            }
            socket.connect(new InetSocketAddress(host, config.loginPort()), 3000);
            socket.setSoTimeout(10_000);
            socket.startHandshake(); // certificate is checked here

            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (reply == null) {
                throw new LoginException("Server closed the connection without answering.");
            }
            String[] p = Protocol.split(reply, 3);
            if ("OK".equals(p[0]) && p.length == 3) {
                return new LoginResult(p[1], p[2]);
            }
            throw new LoginException(p.length > 1 ? p[1] : reply); // e.g. "wrong username or password"
        } catch (SSLHandshakeException e) {
            throw new LoginException("Certificate problem: the server's certificate is not trusted or is not valid for '"
                    + host + "'. Use the lecturer's truststore, and generate the certificate with the LAN IP "
                    + "(scripts/gen-keystore <ip>). Details: " + e.getMessage());
        } catch (UnknownHostException e) {
            throw new LoginException("Unknown host '" + host + "'.");
        } catch (ConnectException e) {
            throw new LoginException("Cannot reach the login service at " + host + ":" + config.loginPort()
                    + ". Is the server running? Is the firewall open?");
        } catch (SocketTimeoutException e) {
            throw new LoginException("The server did not answer in time.");
        } catch (IOException e) {
            throw new LoginException("Network error: " + e.getMessage());
        }
    }
}
