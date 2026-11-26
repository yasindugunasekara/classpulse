// Owner: Member 2, Swing GUI | Lecture topic: Client entry point (login, then the dashboard)
package com.classpulse.client;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Starts the Lecturer Dashboard. Optional argument: server host (default localhost). */
public class LecturerMain {

    public static void main(String[] args) {
        Config config = Config.load();
        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel is fine
            }
            LoginDialog.Result r = new LoginDialog(null, "ClassPulse lecturer login", host, "lecturer", false, config).showDialog();
            if (r == null) {
                System.exit(0);
            }
            if (!Protocol.ROLE_LECTURER.equals(r.role())) {
                JOptionPane.showMessageDialog(null, "This account is not a lecturer account.");
                System.exit(0);
            }
            new LecturerDashboard(config, r).setVisible(true);
        });
    }
}
