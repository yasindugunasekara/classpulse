// Owner: Member 4, Export | Lecture topic: Producing data files for transfer over TCP
package com.classpulse.export;

import com.classpulse.engine.AttendanceRecord;
import com.classpulse.engine.Poll;
import com.classpulse.server.nio.ClassSession;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Builds the two CSV reports. Pure functions: no shared state, so any pool thread may call them. */
public final class CsvBuilder {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private CsvBuilder() { }

    /** username,time,ip,hostname,status */
    public static String attendance(ClassSession s, List<AttendanceRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(s.moduleCode()).append(" - ").append(s.title()).append(" (join code ").append(s.joinCode()).append(")\n");
        sb.append("username,time,ip,hostname,status\n");
        for (AttendanceRecord r : records) {
            sb.append(esc(r.username())).append(',')
              .append(TIME.format(Instant.ofEpochMilli(r.timeMillis()))).append(',')
              .append(esc(r.ip())).append(',')
              .append(esc(r.hostname())).append(',')
              .append(r.status()).append('\n');
        }
        return sb.toString();
    }

    /** One row per poll option: pollId,question,option,votes,percent,totalVotes,state */
    public static String polls(ClassSession s, List<Poll> polls) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(s.moduleCode()).append(" - ").append(s.title()).append(" (join code ").append(s.joinCode()).append(")\n");
        sb.append("pollId,question,option,votes,percent,totalVotes,state\n");
        for (Poll p : polls) {
            int[] counts = p.finalCounts() != null ? p.finalCounts() : p.snapshot();
            int total = 0;
            for (int c : counts) {
                total += c;
            }
            for (int i = 0; i < counts.length; i++) {
                double pct = total == 0 ? 0 : 100.0 * counts[i] / total;
                sb.append(p.id()).append(',')
                  .append(esc(p.question())).append(',')
                  .append(esc(p.options().get(i))).append(',')
                  .append(counts[i]).append(',')
                  .append(String.format(Locale.ROOT, "%.1f", pct)).append(',')
                  .append(total).append(',')
                  .append(p.state()).append('\n');
            }
        }
        return sb.toString();
    }

    /** CSV escaping: wrap in quotes if the value contains a comma, quote or newline. */
    static String esc(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
