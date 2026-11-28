// Owner: Member 2, Swing GUI | Lecture topic: Client entry point
package com.classpulse.client;

import com.classpulse.common.Config;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Starts the Student App. Optional argument: server host for the manual field (default localhost). */
public class StudentMain {

    public static void main(String[] args) {
        Config config = Config.load();
        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine
            }
            new StudentApp(config, host).setVisible(true);
        });
    }
}
