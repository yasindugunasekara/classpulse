// Owner: Member 3, URLs and HTTP | Lecture topic: java.net.URL, HttpURLConnection, timeouts
package com.classpulse.addressing;

import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;
import com.classpulse.common.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Imports poll questions from a CSV file on a web server, e.g. http://192.168.1.10:8080/questions.csv
 * CSV line format:  question,timeLimitSec,option1,option2[,option3...option6]
 *
 * Downloading can take seconds, so it NEVER runs on the NIO thread. It runs on a 2-thread pool
 * ("url-import-N") and the result is handed back to the NIO thread through a callback
 * (the server wraps it in server.submit()).
 */
public class QuestionImporter {

    private static final String COMPONENT = "URL-Import";
    private static final int MAX_BYTES = 256 * 1024;

    /** One parsed question. */
    public record ImportedQuestion(String question, int timeLimitSec, List<String> options) { }

    /** Result of an import: questions found, number of skipped lines, or an error message. */
    public record ImportResult(List<ImportedQuestion> questions, int skipped, String error) { }

    private final ExecutorService pool = Executors.newFixedThreadPool(2, new NamedThreadFactory("url-import", true));

    public void importAsync(String url, Consumer<ImportResult> callback) {
        pool.execute(() -> callback.accept(importNow(url)));
    }

    /** Blocking import (runs on a pool thread). */
    public ImportResult importNow(String urlText) {
        URL url;
        try {
            url = new URI(urlText.trim()).toURL(); // parses scheme, host, port, path
        } catch (Exception e) {
            return new ImportResult(List.of(), 0, "not a valid URL: " + urlText);
        }
        String scheme = url.getProtocol();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return new ImportResult(List.of(), 0, "only http and https URLs are allowed");
        }
        Log.info(COMPONENT, "fetching " + url + " (host=" + url.getHost() + ", port=" + url.getPort()
                + ", path=" + url.getPath() + ")");

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000); // max 3 s to connect
            conn.setReadTimeout(5000);    // max 5 s of silence while reading
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return new ImportResult(List.of(), 0, "web server answered HTTP " + code);
            }
            String type = conn.getContentType();
            if (type != null && !(type.startsWith("text/") || type.contains("csv"))) {
                return new ImportResult(List.of(), 0, "expected a CSV/text file but got " + type);
            }
            if (conn.getContentLengthLong() > MAX_BYTES) {
                return new ImportResult(List.of(), 0, "file too large (max 256 KB)");
            }
            String body = readLimited(conn.getInputStream());
            if (body == null) {
                return new ImportResult(List.of(), 0, "file too large (max 256 KB)");
            }
            return parse(body);
        } catch (UnknownHostException e) {
            return new ImportResult(List.of(), 0, "unknown host " + url.getHost());
        } catch (SocketTimeoutException e) {
            return new ImportResult(List.of(), 0, "web server timed out");
        } catch (IOException e) {
            return new ImportResult(List.of(), 0, "download failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Reads at most 256 KB; returns null if the stream is longer (protects server memory). */
    private static String readLimited(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    return null;
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Parses the CSV text; bad lines are skipped and counted. */
    static ImportResult parse(String body) {
        List<ImportedQuestion> list = new ArrayList<>();
        int skipped = 0;
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.toLowerCase().startsWith("question,")) {
                continue; // blank, comment or header line
            }
            String[] f = line.split(",");
            if (f.length < 4 || f.length > 8) {
                skipped++;
                continue;
            }
            int time = Protocol.parseIntOr(f[1], -1);
            String question = Protocol.sanitizeFreeText(f[0]);
            List<String> options = new ArrayList<>();
            for (int i = 2; i < f.length; i++) {
                String o = Protocol.sanitize(f[i]);
                if (!o.isEmpty()) {
                    options.add(o);
                }
            }
            if (question.isEmpty() || time < 10 || time > 600 || options.size() < 2) {
                skipped++;
                continue;
            }
            list.add(new ImportedQuestion(question, time, options));
        }
        Log.info(COMPONENT, "parsed " + list.size() + " questions, skipped " + skipped + " bad lines");
        return new ImportResult(list, skipped, null);
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
