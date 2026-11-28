// Owner: Member 5, Secure networking | Lecture topic: Authentication and authorisation (who you are + what you may do)
package com.classpulse.security;

/**
 * An authenticated user: the username and the role (LECTURER or STUDENT).
 * The NIO server receives only a token; TokenStore.validate(token) turns it back into a Principal.
 */
public record Principal(String username, String role) {

    public boolean isLecturer() {
        return "LECTURER".equals(role);
    }

    public boolean isStudent() {
        return "STUDENT".equals(role);
    }
}
