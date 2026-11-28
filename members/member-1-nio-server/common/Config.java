// Owner: Member 1, Java NIO (shared configuration) | Lecture topic: Configurable network services (ports, hosts)
package com.classpulse.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Reads config.properties from the project root (or the file named by -Dclasspulse.config=...).
 * Every value has a default, so the system still starts if the file is missing.
 * The Config object is created once and passed to every service by constructor.
 */
public final class Config {

    private final Properties props = new Properties();

    public Config(Properties props) {
        this.props.putAll(props);
    }

    /** Load the default config file. Missing file = all defaults. */
    public static Config load() {
        String fileName = System.getProperty("classpulse.config", "config.properties");
        Path path = Paths.get(fileName);
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("Could not read " + path + ": " + e.getMessage() + " (using defaults)");
            }
        }
        // -Dkey=value on the command line overrides the file (handy for tests).
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("port.") || key.startsWith("attendance.") || key.startsWith("debug.")
                    || key.startsWith("poll.") || key.startsWith("tls.") || key.startsWith("export.")) {
                p.setProperty(key, System.getProperty(key));
            }
        }
        return new Config(p);
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    public int getInt(String key, int def) {
        return Protocol.parseIntOr(get(key, String.valueOf(def)), def);
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    // ---- Convenience getters for the ports (all configurable) ----
    public int classroomPort() { return getInt("port.classroom", 6000); }
    public int loginPort() { return getInt("port.login", 6001); }
    public int discoveryPort() { return getInt("port.discovery", 6002); }
    public int exportPort() { return getInt("port.export", 6003); }
    public int announcePort() { return getInt("port.announce", 6004); }
    public int questionWebPort() { return getInt("port.questionweb", 8080); }
}
