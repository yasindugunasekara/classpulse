// Owner: Member 5, Secure networking (test) | Lecture topic: Verifying TLS login, hashing and rotating codes
package com.classpulse.tests;

import com.classpulse.common.Config;
import com.classpulse.security.AttendanceCodeGenerator;
import com.classpulse.security.SecureLoginClient;
import com.classpulse.security.SecureLoginServer;
import com.classpulse.security.TokenStore;
import com.classpulse.security.UserStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Phase 2 check: register, login, wrong password, users.txt has hashes only,
 * attendance code rotation and validation (current + previous window OK, older rejected).
 * Run: java -cp out com.classpulse.tests.SecurityTest
 */
public class SecurityTest {

    public static void main(String[] args) throws Exception {
        Path usersFile = Files.createTempDirectory("cp-sec").resolve("users.txt");
        Properties p = new Properties();
        p.setProperty("port.login", "16001"); // separate port so it does not clash with a running server
        Config config = new Config(p);

        UserStore users = new UserStore(usersFile);
        TokenStore tokens = new TokenStore();
        SecureLoginServer server = new SecureLoginServer(config, users, tokens);
        server.start();
        SecureLoginClient client = new SecureLoginClient(config);

        SecureLoginClient.LoginResult r = client.login("localhost", "lecturer", "lecturer123");
        check("lecturer login gives LECTURER token", "LECTURER".equals(r.role()) && tokens.validate(r.token()) != null);

        boolean rejected = false;
        try {
            client.login("localhost", "lecturer", "wrong-pass");
        } catch (SecureLoginClient.LoginException e) {
            rejected = e.getMessage().contains("wrong");
        }
        check("wrong password rejected", rejected);

        SecureLoginClient.LoginResult reg = client.register("localhost", "newuser_1", "secret99");
        check("register creates STUDENT", "STUDENT".equals(reg.role()));
        check("registered user can log in", client.login("localhost", "newuser_1", "secret99") != null);

        boolean dup = false;
        try {
            client.register("localhost", "newuser_1", "secret99");
        } catch (SecureLoginClient.LoginException e) {
            dup = e.getMessage().contains("taken");
        }
        check("duplicate username rejected", dup);

        String fileText = Files.readString(usersFile);
        check("users.txt contains no plain passwords",
                !fileText.contains("lecturer123") && !fileText.contains("secret99") && !fileText.contains("s001pass"));

        // ---- rotating attendance codes ----
        AttendanceCodeGenerator gen = new AttendanceCodeGenerator(30);
        gen.startSession("ABC123");
        long now = System.currentTimeMillis();
        long w = gen.windowNumber(now);
        String current = gen.codeForWindow("ABC123", w);
        String previous = gen.codeForWindow("ABC123", w - 1);
        String older = gen.codeForWindow("ABC123", w - 2);
        check("code is 6 chars: " + current, current.length() == 6);
        check("code changes every window", !current.equals(previous));
        check("current code valid", gen.isValidAt("ABC123", current, now));
        check("previous code valid (typing delay)", gen.isValidAt("ABC123", previous, now));
        check("code from 2 windows ago rejected", !gen.isValidAt("ABC123", older, now));
        check("lower-case input accepted", gen.isValidAt("ABC123", current.toLowerCase(), now));
        check("wrong code rejected", !gen.isValidAt("ABC123", "AAAAAA", now) || "AAAAAA".equals(current));

        server.stop();
        System.out.println("SecurityTest: ALL PASSED");
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) {
            System.exit(1);
        }
    }
}
