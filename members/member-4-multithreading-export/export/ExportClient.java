// Owner: Member 4, Thread pools (client side) | Lecture topic: Background work in GUIs with SwingWorker
package com.classpulse.export;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;

/**
 * Downloads a CSV from the export server (port 6003).
 * In the GUI the download runs in a SwingWorker (a background thread), so the window never freezes;
 * publish()/process() move progress updates back onto the Swing event thread.
 */
public class ExportClient {

    private final Config config;

    public ExportClient(Config config) {
        this.config = config;
    }

    /** Blocking download. Returns the server's file name. progress receives 0..100. */
    public String download(String host, String token, String type, String joinCode, File target, IntConsumer progress)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, config.exportPort()), 3000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write((Protocol.join(Protocol.EXPORT, token, type, joinCode) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            String header = readHeaderLine(in); // read the header byte by byte, so no file bytes are swallowed
            String[] p = Protocol.split(header, 3);
            if (!"OK".equals(p[0]) || p.length < 3) {
                throw new IOException(p.length > 1 ? p[1] : header);
            }
            String fileName = p[1];
            int size = Protocol.parseIntOr(p[2], -1);
            if (size < 0) {
                throw new IOException("bad size in reply");
            }
            byte[] data = new byte[size];
            int got = 0;
            while (got < size) { // read() may return fewer bytes than asked: loop until all arrived
                int n = in.read(data, got, size - got);
                if (n == -1) {
                    throw new IOException("connection closed after " + got + " of " + size + " bytes");
                }
                got += n;
                progress.accept(size == 0 ? 100 : (int) (100L * got / size));
            }
            Files.write(target.toPath(), data);
            progress.accept(100);
            return fileName;
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            buf.write(b);
            if (buf.size() > Protocol.MAX_LINE) {
                throw new IOException("header too long");
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** GUI version: runs download() on a SwingWorker thread and reports to a progress bar. */
    public void downloadInBackground(String host, String token, String type, String joinCode, File target,
                                     JProgressBar bar, Consumer<String> onDone) {
        new SwingWorker<String, Integer>() {
            @Override
            protected String doInBackground() throws Exception {
                return download(host, token, type, joinCode, target, this::publish); // background thread
            }

            @Override
            protected void process(List<Integer> chunks) {
                bar.setValue(chunks.get(chunks.size() - 1)); // Swing event thread
            }

            @Override
            protected void done() {
                try {
                    String name = get();
                    bar.setValue(100);
                    onDone.accept("Saved " + name + " to " + target.getAbsolutePath());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    onDone.accept("Export failed: " + cause.getMessage());
                }
            }
        }.execute();
    }
}
