// Owner: Member 1, Java NIO (shared protocol) | Lecture topic: Application-layer protocol design over TCP/UDP
package com.classpulse.common;

/**
 * Single source of truth for the ClassPulse wire protocol (see docs/PROTOCOL.md).
 *
 * Every TCP control message is ONE line of UTF-8 text ending in '\n'.
 * Fields are separated by '|'. A free-text field (question, title, reason) is always LAST,
 * so we split with a limit and the free text may never break the parsing.
 */
public final class Protocol {

    private Protocol() { }

    /** Field separator inside one line. */
    public static final String SEP = "|";
    /** Separator for option lists / count lists inside one field. */
    public static final String LIST_SEP = ";";
    /** Longest line we accept (bytes). Protects the server from memory abuse. */
    public static final int MAX_LINE = 8192;

    // ---- Roles ----
    public static final String ROLE_LECTURER = "LECTURER";
    public static final String ROLE_STUDENT = "STUDENT";

    // ---- Login service (TLS, port 6001) ----
    public static final String LOGIN = "LOGIN";
    public static final String REGISTER = "REGISTER";

    // ---- Classroom service (NIO, port 6000): client -> server ----
    public static final String AUTH = "AUTH";
    public static final String CREATE_SESSION = "CREATE_SESSION";
    public static final String START_ATTENDANCE = "START_ATTENDANCE";
    public static final String STOP_ATTENDANCE = "STOP_ATTENDANCE";
    public static final String CREATE_POLL = "CREATE_POLL";
    public static final String IMPORT_POLLS = "IMPORT_POLLS";
    public static final String START_POLL = "START_POLL";
    public static final String STOP_POLL = "STOP_POLL";
    public static final String END_SESSION = "END_SESSION";
    public static final String JOIN_SESSION = "JOIN_SESSION";
    public static final String ATTEND = "ATTEND";
    public static final String VOTE = "VOTE";
    public static final String PING = "PING";
    public static final String QUIT = "QUIT";

    // ---- Classroom service: server -> client ----
    public static final String OK = "OK";
    public static final String ERR = "ERR";
    public static final String PONG = "PONG";
    public static final String SESSION = "SESSION";
    public static final String ATT_CODE = "ATT_CODE";
    public static final String ATT_OPEN = "ATT_OPEN";
    public static final String ATT_CLOSED = "ATT_CLOSED";
    public static final String ATT_OK = "ATT_OK";
    public static final String ATT_COUNT = "ATT_COUNT";
    public static final String POLL_LIST = "POLL_LIST";
    public static final String POLL_START = "POLL_START";
    public static final String POLL_UPDATE = "POLL_UPDATE";
    public static final String POLL_END = "POLL_END";
    public static final String VOTE_OK = "VOTE_OK";
    public static final String SYS = "SYS";

    // ---- UDP discovery (port 6002) ----
    public static final String DISCOVER = "CLASSPULSE_DISCOVER";
    public static final String HERE = "CLASSPULSE_HERE";
    public static final String ANNOUNCE = "CLASSPULSE_ANNOUNCE";

    // ---- Export service (port 6003) ----
    public static final String EXPORT = "EXPORT";
    public static final String EXPORT_ATTENDANCE = "ATTENDANCE";
    public static final String EXPORT_POLLS = "POLLS";

    /**
     * Split a line into at most 'limit' fields. The last field keeps any extra '|' characters,
     * which is why free text always goes last.
     */
    public static String[] split(String line, int limit) {
        return line.split("\\|", limit);
    }

    /** Join fields with '|'. */
    public static String join(Object... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(SEP);
            }
            sb.append(fields[i]);
        }
        return sb.toString();
    }

    /** Remove characters that would break our framing: '|', ';', CR and LF. */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "/").replace(";", ",").replace("\r", " ").replace("\n", " ").trim();
    }

    /** Free text (last field) may contain '|' but never a newline. */
    public static String sanitizeFreeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ").trim();
    }

    /** Join an int array as "1;2;3". */
    public static String joinCounts(int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                sb.append(LIST_SEP);
            }
            sb.append(counts[i]);
        }
        return sb.toString();
    }

    /** Parse "1;2;3" back into an int array (bad numbers become 0). */
    public static int[] parseCounts(String field) {
        if (field == null || field.isEmpty()) {
            return new int[0];
        }
        String[] parts = field.split(LIST_SEP);
        int[] counts = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            counts[i] = parseIntOr(parts[i], 0);
        }
        return counts;
    }

    /** Parse an int without throwing: never trust client-supplied numbers. */
    public static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static long parseLongOr(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
