// Owner: Member 2, TCP client + Swing GUI | Lecture topic: A GUI client using TCP (classroom) and UDP (discovery)
package com.classpulse.client;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;
import com.classpulse.security.SecureLoginClient;
import com.classpulse.udp.SessionInfo;
import com.classpulse.udp.SessionScanner;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The student's window:
 *  1. lists sessions found automatically on the LAN (Member 3's UDP SessionScanner),
 *  2. logs in over TLS (Member 5) and connects to the NIO server over TCP (ClassroomClient),
 *  3. marks attendance with the code on the projector,
 *  4. answers polls with a countdown and sees the live results.
 */
public class StudentApp extends JFrame implements ServerListener {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final transient Config config;
    private final transient SessionScanner scanner;
    private transient ClassroomClient client;

    // discovery + join
    private final DefaultListModel<SessionInfo> sessionModel = new DefaultListModel<>();
    private final JList<SessionInfo> sessionList = new JList<>(sessionModel);
    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField joinCodeField = new JTextField(7);
    private final JLabel connLabel = new JLabel("Not connected");
    private final JLabel sessionLabel = new JLabel(" ");

    // attendance
    private final JTextField codeField = new JTextField(7);
    private final JButton markButton = new JButton("Mark attendance");
    private final JLabel attStatus = new JLabel("Attendance is not open yet");

    // poll
    private final JLabel questionLabel = new JLabel("No poll running");
    private final JPanel optionsPanel = new JPanel(new GridLayout(0, 1));
    private final JButton voteButton = new JButton("Vote");
    private final JLabel pollTimer = new JLabel(" ");
    private final BarChartPanel chart = new BarChartPanel();
    private ButtonGroup optionGroup = new ButtonGroup();
    private List<JRadioButton> optionButtons = new ArrayList<>();
    private String currentPollId;
    private long pollEndsAt;

    private final JTextArea log = new JTextArea(5, 60);

    public StudentApp(Config config, String defaultHost) {
        super("ClassPulse - Student");
        this.config = config;
        hostField.setText(defaultHost);
        buildUi();
        // UDP discovery runs in the background; its callback comes from a UDP thread
        scanner = new SessionScanner(config, list -> SwingUtilities.invokeLater(() -> showSessions(list)));
        scanner.start();
        new Timer(250, e -> tick()).start();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (client != null) {
                    client.close();
                }
                scanner.stop();
                System.exit(0);
            }
        });

        // --- top: sessions on the network
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.setVisibleRowCount(4);
        sessionList.addListSelectionListener(e -> {
            SessionInfo s = sessionList.getSelectedValue();
            if (s != null) {
                hostField.setText(s.host());
                joinCodeField.setText(s.joinCode());
            }
        });
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> new Thread(scanner::refreshNow, "udp-refresh").start());
        JButton join = new JButton("Login & join");
        join.addActionListener(e -> loginAndJoin());
        JButton reconnect = new JButton("Reconnect");
        reconnect.addActionListener(e -> {
            if (client != null) {
                new Thread(client::reconnectNow, "reconnect").start();
            }
        });

        JPanel manual = new JPanel(new FlowLayout(FlowLayout.LEFT));
        manual.add(new JLabel("Host (manual fallback):"));
        manual.add(hostField);
        manual.add(new JLabel("Join code:"));
        manual.add(joinCodeField);
        manual.add(join);
        manual.add(refresh);
        manual.add(reconnect);
        JPanel status = new JPanel(new GridLayout(2, 1));
        status.add(connLabel);
        status.add(sessionLabel);
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(" Sessions found on this network (UDP discovery, updates automatically):"), BorderLayout.NORTH);
        top.add(new JScrollPane(sessionList), BorderLayout.CENTER);
        JPanel topSouth = new JPanel(new BorderLayout());
        topSouth.add(manual, BorderLayout.NORTH);
        topSouth.add(status, BorderLayout.SOUTH);
        top.add(topSouth, BorderLayout.SOUTH);

        // --- attendance
        JPanel att = new JPanel(new FlowLayout(FlowLayout.LEFT));
        codeField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        markButton.setEnabled(false);
        markButton.addActionListener(e -> {
            if (client != null) {
                client.send(Protocol.join(Protocol.ATTEND, codeField.getText().trim().toUpperCase()));
            }
        });
        att.add(new JLabel("Code on the screen:"));
        att.add(codeField);
        att.add(markButton);
        att.add(attStatus);
        att.setBorder(BorderFactory.createTitledBorder("Attendance"));

        // --- poll
        questionLabel.setFont(questionLabel.getFont().deriveFont(Font.BOLD, 16f));
        pollTimer.setFont(pollTimer.getFont().deriveFont(Font.BOLD, 14f));
        voteButton.setEnabled(false);
        voteButton.addActionListener(e -> vote());
        JPanel pollLeft = new JPanel(new BorderLayout(4, 4));
        pollLeft.add(questionLabel, BorderLayout.NORTH);
        pollLeft.add(optionsPanel, BorderLayout.CENTER);
        JPanel vb = new JPanel(new FlowLayout(FlowLayout.LEFT));
        vb.add(voteButton);
        vb.add(pollTimer);
        pollLeft.add(vb, BorderLayout.SOUTH);
        JPanel poll = new JPanel(new GridLayout(1, 2, 8, 8));
        poll.add(pollLeft);
        poll.add(chart);
        poll.setBorder(BorderFactory.createTitledBorder("Live poll"));

        JPanel center = new JPanel(new BorderLayout());
        center.add(att, BorderLayout.NORTH);
        center.add(poll, BorderLayout.CENTER);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(new JScrollPane(log), BorderLayout.SOUTH);
        setSize(900, 700);
        setLocationRelativeTo(null);
    }

    private void showSessions(List<SessionInfo> list) {
        SessionInfo selected = sessionList.getSelectedValue();
        sessionModel.clear();
        for (SessionInfo s : list) {
            sessionModel.addElement(s);
        }
        if (selected != null) {
            for (int i = 0; i < sessionModel.size(); i++) {
                if (sessionModel.get(i).joinCode().equals(selected.joinCode())) {
                    sessionList.setSelectedIndex(i); // keep the selection across refreshes
                }
            }
        } else if (sessionModel.size() == 1 && joinCodeField.getText().isEmpty()) {
            sessionList.setSelectedIndex(0); // only one session: pre-select it
        }
    }

    private void loginAndJoin() {
        String code = joinCodeField.getText().trim().toUpperCase();
        if (code.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select a session from the list or type the join code.");
            return;
        }
        LoginDialog.Result r = new LoginDialog(this, "Student login", hostField.getText().trim(), "s001", true, config).showDialog();
        if (r == null) {
            return;
        }
        if (!Protocol.ROLE_STUDENT.equals(r.role())) {
            JOptionPane.showMessageDialog(this, "Please log in with a student account.");
            return;
        }
        if (client != null) {
            client.close();
        }
        setTitle("ClassPulse - Student (" + r.username() + ")");
        SecureLoginClient tls = new SecureLoginClient(config);
        client = new ClassroomClient(r.host(), config.classroomPort(),
                () -> tls.login(r.host(), r.username(), r.password()).token(), this);
        client.rememberJoinCode(code); // sent after AUTH, and again after every reconnect
        ClassroomClient c = client;
        new Thread(() -> {
            try {
                c.connect(r.token());
            } catch (Exception ex) {
                onConnectionStatus("Cannot connect: " + ClassroomClient.friendly(ex), false);
            }
        }, "connect").start();
    }

    private void vote() {
        for (int i = 0; i < optionButtons.size(); i++) {
            if (optionButtons.get(i).isSelected()) {
                client.send(Protocol.join(Protocol.VOTE, currentPollId, i));
                return;
            }
        }
        JOptionPane.showMessageDialog(this, "Choose an option first.");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (pollEndsAt > now) {
            pollTimer.setText("Time left: " + ((pollEndsAt - now + 999) / 1000) + " s");
        }
    }

    // ------------------------------------------------------------------ server messages

    @Override
    public void onLine(String line) {
        SwingUtilities.invokeLater(() -> handle(line));
    }

    @Override
    public void onConnectionStatus(String message, boolean connected) {
        SwingUtilities.invokeLater(() -> {
            connLabel.setText(message);
            connLabel.setForeground(connected ? new Color(0, 120, 0) : new Color(170, 0, 0));
            appendLog(message);
        });
    }

    private void handle(String line) {
        String[] p = Protocol.split(line, 5);
        switch (p[0]) {
            case Protocol.SESSION -> {
                String[] s = Protocol.split(line, 4);
                sessionLabel.setText("Joined " + s[2] + " - " + s[3] + "  (join code " + s[1] + ")");
            }
            case Protocol.ATT_OPEN -> {
                if (!attStatus.getText().startsWith("Attendance marked")) {
                    markButton.setEnabled(true);
                    attStatus.setText("Attendance is OPEN: type the code shown on the lecturer's screen");
                    attStatus.setForeground(new Color(0, 90, 160));
                }
            }
            case Protocol.ATT_CLOSED -> {
                markButton.setEnabled(false);
                if (!attStatus.getText().startsWith("Attendance marked")) {
                    attStatus.setText("Attendance is closed");
                    attStatus.setForeground(Color.DARK_GRAY);
                }
            }
            case Protocol.ATT_OK -> {
                String time = Instant.ofEpochMilli(Protocol.parseLongOr(p[1], 0)).atZone(ZoneId.systemDefault()).format(HM);
                attStatus.setText("Attendance marked at " + time);
                attStatus.setForeground(new Color(0, 120, 0));
                markButton.setEnabled(false);
            }
            case Protocol.POLL_START -> showPoll(Protocol.split(line, 5));
            case Protocol.VOTE_OK -> {
                voteButton.setEnabled(false);
                for (JRadioButton b : optionButtons) {
                    b.setEnabled(false);
                }
                pollTimer.setText("Vote received!");
                chart.setTitle("Live results");
                pollEndsAt = 0;
            }
            case Protocol.POLL_UPDATE -> {
                if (p[1].equals(currentPollId)) {
                    chart.setCounts(Protocol.parseCounts(p[2]), Protocol.parseIntOr(p[3], 0));
                }
            }
            case Protocol.POLL_END -> {
                if (p[1].equals(currentPollId)) {
                    chart.setCounts(Protocol.parseCounts(p[2]), Protocol.parseIntOr(p[3], 0));
                    voteButton.setEnabled(false);
                    for (JRadioButton b : optionButtons) {
                        b.setEnabled(false);
                    }
                    pollEndsAt = 0;
                    chart.setTitle("Final result");
                    pollTimer.setText("Poll closed");
                }
            }
            case Protocol.SYS -> {
                appendLog(line.substring(4));
                if (line.contains("Session ended")) {
                    sessionLabel.setText("The lecturer ended the session.");
                    markButton.setEnabled(false);
                }
            }
            case Protocol.ERR -> {
                String reason = line.length() > 4 ? line.substring(4) : line;
                appendLog("ERROR: " + reason);
                if (reason.contains("attendance") || reason.contains("code")) {
                    attStatus.setText(reason);
                    attStatus.setForeground(new Color(170, 0, 0));
                } else if (reason.contains("poll") || reason.contains("voted")) {
                    pollTimer.setText(reason);
                } else {
                    connLabel.setText(reason);
                }
            }
            default -> { }
        }
    }

    private void showPoll(String[] s) {
        currentPollId = s[1];
        List<String> options = Arrays.asList(s[3].split(Protocol.LIST_SEP));
        questionLabel.setText("<html><body style='width:380px'>" + escapeHtml(s[4]) + "</body></html>");
        optionsPanel.removeAll();
        optionGroup = new ButtonGroup();
        optionButtons = new ArrayList<>();
        for (String o : options) {
            JRadioButton b = new JRadioButton(o);
            b.setFont(b.getFont().deriveFont(15f));
            optionGroup.add(b);
            optionButtons.add(b);
            optionsPanel.add(b);
        }
        optionsPanel.revalidate();
        optionsPanel.repaint();
        voteButton.setEnabled(true);
        pollEndsAt = System.currentTimeMillis() + Protocol.parseIntOr(s[2], 0) * 1000L;
        chart.setPoll("Results appear after you vote", options);
        appendLog("New poll: " + s[4]);
        toFront(); // the question "pops up"
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void appendLog(String text) {
        log.append(LocalTime.now().format(HMS) + "  " + text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }
}
