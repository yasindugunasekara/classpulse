// Owner: Member 3, URLs and HTTP (demo tool) | Lecture topic: A minimal HTTP server with the JDK's HttpServer
package com.classpulse.tools;

import com.classpulse.common.Config;
import com.classpulse.common.Log;
import com.classpulse.common.NamedThreadFactory;
import com.classpulse.addressing.NetworkGuard;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

/**
 * Serves docs/sample-questions.csv at http://<this-pc>:8080/questions.csv, so the URL import demo
 * works without internet. Uses com.sun.net.httpserver.HttpServer, which is part of the JDK.
 */
public class QuestionWebServer {

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        int port = config.questionWebPort();
        Path file = Paths.get(args.length > 0 ? args[0] : "docs/sample-questions.csv");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/questions.csv", exchange -> {
            Log.info("QuestionWeb", exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " from " + exchange.getRemoteAddress());
            if (!Files.exists(file)) {
                exchange.sendResponseHeaders(404, -1); // HTTP 404 Not Found, no body
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length); // HTTP 200 OK + Content-Length
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, new NamedThreadFactory("http-worker", false)));
        server.start();
        String ip = NetworkGuard.lanAddress().getHostAddress();
        System.out.println("Question web server running. Import URL:");
        System.out.println("   http://localhost:" + port + "/questions.csv");
        System.out.println("   http://" + ip + ":" + port + "/questions.csv   (from other laptops)");
    }
}
