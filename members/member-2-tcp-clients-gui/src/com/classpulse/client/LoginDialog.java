// Owner: Member 2, Swing GUI (uses Member 5's TLS client) | Lecture topic: Doing network calls off the Swing thread
package com.classpulse.client;

import com.classpulse.common.Config;
import com.classpulse.security.SecureLoginClient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * Login / register dialog shared by both apps. The TLS login is a network call that can take a
 * second, so it runs in a SwingWorker; the dialog stays responsive and shows the status.
 */
public class LoginDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** What the dialog returns after a successful login. */
    public record Result(String host, String username, String password, String token, String role) { }

    private final transient SecureLoginClient loginClient;
    private final JTextField hostField;
    private final JTextField userField = new JTextField(14);
    private final JPasswordField passField = new JPasswordField(14);
    private final JLabel status = new JLabel(" ");
    private final JButton loginButton = new JButton("Login");
    private final JButton registerButton = new JButton("Register");
    private transient Result result;

    public LoginDialog(Frame owner, String title, String defaultHost, String defaultUser, boolean allowRegister, Config config) {
        super(owner, title, true);
        this.loginClient = new SecureLoginClient(config);
        this.hostField = new JTextField(defaultHost, 14);
        userField.setText(defaultUser);

        JPanel form = new JPanel(new GridLayout(3, 2, 6, 6));
        form.add(new JLabel("Server host:"));
        form.add(hostField);
        form.add(new JLabel(allowRegister ? "Index number / username:" : "Username:"));
        form.add(userField);
        form.add(new JLabel("Password:"));
        form.add(passField);
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(loginButton);
        if (allowRegister) {
            buttons.add(registerButton);
        }
        JButton cancel = new JButton("Cancel");
        buttons.add(cancel);

        status.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        JPanel south = new JPanel(new BorderLayout());
        south.add(status, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        add(new JLabel("  Login is sent over TLS (encrypted). Your password never travels in plain text."), BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        loginButton.addActionListener(e -> submit(false));
        registerButton.addActionListener(e -> submit(true));
        cancel.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(loginButton);
        pack();
        setLocationRelativeTo(owner);
    }

    private void submit(boolean register) {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String pass = new String(passField.getPassword());
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showStatus("Please fill in host, username and password.", true);
            return;
        }
        loginButton.setEnabled(false);
        registerButton.setEnabled(false);
        showStatus(register ? "Registering over TLS..." : "Logging in over TLS...", false);

        new SwingWorker<SecureLoginClient.LoginResult, Void>() {
            @Override
            protected SecureLoginClient.LoginResult doInBackground() throws Exception {
                // background thread: TLS handshake + request
                return register ? loginClient.register(host, user, pass) : loginClient.login(host, user, pass);
            }

            @Override
            protected void done() {
                // back on the Swing thread
                loginButton.setEnabled(true);
                registerButton.setEnabled(true);
                try {
                    SecureLoginClient.LoginResult r = get();
                    result = new Result(host, user, pass, r.token(), r.role());
                    dispose();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showStatus(cause.getMessage(), true);
                }
            }
        }.execute();
    }

    private void showStatus(String text, boolean error) {
        status.setForeground(error ? new Color(170, 0, 0) : new Color(0, 90, 0));
        status.setText("<html><body style='width:330px'>" + text + "</body></html>");
        pack();
    }

    /** Shows the dialog and returns the result, or null if cancelled. */
    public Result showDialog() {
        setVisible(true); // modal: blocks here until dispose()
        return result;
    }
}
