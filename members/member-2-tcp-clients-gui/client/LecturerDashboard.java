// Owner: Member 2, TCP client + Swing GUI | Lecture topic: A GUI driven by messages from a TCP socket
package com.classpulse.client;

import com.classpulse.common.Config;
import com.classpulse.common.Protocol;
import com.classpulse.export.ExportClient;
import com.classpulse.security.SecureLoginClient;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The lecturer's window. Every button sends ONE protocol line through ClassroomClient;
 * every line from the server arrives in onLine() on the reader thread and is applied to the
 * widgets with SwingUtilities.invokeLater (Swing is not thread-safe).
 */
public class LecturerDashboard extends JFrame implements ServerListener {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final transient Config config;
    private final transient LoginDialog.Result login;
    private final transient ClassroomClient client;
    private final transient ExportClient exportClient;
    private volatile String token;

    // session
    private final JTextField moduleField = new JTextField("IN2120", 8);
    private final JTextField titleField = new JTextField("Network Programming", 18);
    private final JLabel joinCodeLabel = big("------", 30f);
    private final JLabel sessionLabel = new JLabel("No session yet");
    private final JLabel connLabel = new JLabel("Connecting...");
    private String joinCode;

    // attendance
    private final JLabel attCodeLabel = big("------", 84f);
    private final JLabel attCodeTimer = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel attCloseTimer = new JLabel("Attendance is closed", SwingConstants.CENTER);
    private final JLabel attCount = big("0 / 0", 26f);
    private final JSpinner attDuration = new JSpinner(new SpinnerNumberModel(300, 30, 3600, 30));
    private final JTextArea warnings = new JTextArea(5, 40);
    private long codeChangesAt;
    private long attClosesAt;

    // polls
    private final JTextField questionField = new JTextField("Which layer does TCP work in?", 30);
    private final JTextArea optionsArea = new JTextArea("Application\nTransport\nNetwork\nData link", 5, 30);
    private final JSpinner pollTime = new JSpinner(new SpinnerNumberModel(30, 10, 600, 5));
    private final JTextField urlField = new JTextField("http://localhost:8080/questions.csv", 30);
    private final DefaultListModel<String> pollModel = new DefaultListModel<>();
    private final JList<String> pollList = new JList<>(pollModel);
    private final BarChartPanel chart = new BarChartPanel();
    private final JLabel pollTimer = new JLabel(" ");
    private long pollEndsAt;

    // export + log
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel exportStatus = new JLabel(" ");
    private final JTextArea log = new JTextArea(6, 80);

    public LecturerDashboard(Config config, LoginDialog.Result login) {
        super("ClassPulse - Lecturer Dashboard (" + login.username() + ")");
        this.config = config;
        this.login = login;
        this.token = login.token();
        this.exportClient = new ExportClient(config);
        SecureLoginClient tls = new SecureLoginClient(config);
        // on reconnect: log in again over TLS (the server may have restarted and forgotten old tokens)
        this.client = new ClassroomClient(login.host(), config.classroomPort(), () -> {
            String t = tls.login(login.host(), login.username(), login.password()).token();
            token = t;
            return t;
        }, this);
        urlField.setText("http://" + ("localhost".equals(login.host()) ? "localhost" : login.host()) + ":"
                + config.questionWebPort() + "/questions.csv");
        buildUi();
        new Timer(250, e -> tick()).start(); // countdown labels
        connectInBackground();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                client.close();
                System.exit(0);
            }
        });

        // --- top: session
        JPanel top = new JPanel(new BorderLayout());
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT));
        form.add(new JLabel("Module:"));
        form.add(moduleField);
        form.add(new JLabel("Title:"));
        form.add(titleField);
        JButton create = new JButton("Create session");
        create.addActionListener(e -> client.send(Protocol.join(Protocol.CREATE_SESSION,
                Protocol.sanitize(moduleField.getText()), Protocol.sanitizeFreeText(titleField.getText()))));
        form.add(create);
        JButton end = new JButton("End session");
        end.addActionListener(e -> client.send(Protocol.END_SESSION));
        form.add(end);
        JPanel codeBox = new JPanel(new BorderLayout());
        codeBox.add(new JLabel("Join code", SwingConstants.CENTER), BorderLayout.NORTH);
        codeBox.add(joinCodeLabel, BorderLayout.CENTER);
        codeBox.add(sessionLabel, BorderLayout.SOUTH);
        codeBox.setBorder(BorderFactory.createEmptyBorder(4, 16, 4, 16));
        JPanel conn = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton reconnect = new JButton("Reconnect");
        reconnect.addActionListener(e -> new Thread(client::reconnectNow, "reconnect").start());
        conn.add(connLabel);
        conn.add(reconnect);
        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);
        left.add(conn, BorderLayout.SOUTH);
        top.add(left, BorderLayout.CENTER);
        top.add(codeBox, BorderLayout.EAST);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Attendance", attendancePanel());
        tabs.addTab("Polls", pollPanel());
        tabs.addTab("Export", exportPanel());

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, new JScrollPane(log));
        split.setResizeWeight(0.8);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setSize(1000, 720);
        setLocationRelativeTo(null);
    }

    private JPanel attendancePanel() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        attCodeLabel.setForeground(new Color(0x1E3799));
        attCodeLabel.setAlignmentX(0.5f);
        attCodeTimer.setAlignmentX(0.5f);
        attCloseTimer.setAlignmentX(0.5f);
        attCount.setAlignmentX(0.5f);
        center.add(centered("Attendance code (type it in the Student App)"));
        center.add(attCodeLabel);
        center.add(attCodeTimer);
        center.add(Box.createVerticalStrut(8));
        center.add(attCloseTimer);
        center.add(Box.createVerticalStrut(8));
        center.add(centered("Present / joined"));
        center.add(attCount);

        JPanel buttons = new JPanel(new FlowLayout());
        buttons.add(new JLabel("Duration (s):"));
        buttons.add(attDuration);
        JButton start = new JButton("Start attendance");
        start.addActionListener(e -> client.send(Protocol.join(Protocol.START_ATTENDANCE, attDuration.getValue())));
        JButton stop = new JButton("Stop attendance");
        stop.addActionListener(e -> client.send(Protocol.STOP_ATTENDANCE));
        buttons.add(start);
        buttons.add(stop);

        warnings.setEditable(false);
        warnings.setForeground(new Color(150, 60, 0));
        JPanel warn = new JPanel(new BorderLayout());
        warn.add(new JLabel(" Warnings (same device used by several students: a hint, not proof)"), BorderLayout.NORTH);
        warn.add(new JScrollPane(warnings), BorderLayout.CENTER);

        p.add(buttons, BorderLayout.NORTH);
        p.add(center, BorderLayout.CENTER);
        p.add(warn, BorderLayout.SOUTH);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }

    private JPanel pollPanel() {
        JPanel editor = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        editor.add(new JLabel("Question:"), c);
        c.gridx = 1;
        editor.add(questionField, c);
        c.gridx = 0;
        c.gridy = 1;
        editor.add(new JLabel("Options (one per line, 2-6):"), c);
        c.gridx = 1;
        editor.add(new JScrollPane(optionsArea), c);
        c.gridx = 0;
        c.gridy = 2;
        editor.add(new JLabel("Time limit (s):"), c);
        c.gridx = 1;
        JButton createPoll = new JButton("Create poll");
        createPoll.addActionListener(e -> createPoll());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(pollTime);
        row.add(Box.createHorizontalStrut(10));
        row.add(createPoll);
        editor.add(row, c);
        c.gridx = 0;
        c.gridy = 3;
        editor.add(new JLabel("Import CSV from URL:"), c);
        c.gridx = 1;
        JButton importBtn = new JButton("Import");
        importBtn.addActionListener(e -> client.send(Protocol.join(Protocol.IMPORT_POLLS, urlField.getText().trim())));
        JPanel urlRow = new JPanel(new BorderLayout(6, 0));
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.add(importBtn, BorderLayout.EAST);
        editor.add(urlRow, c);

        pollList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(new JLabel("Polls of this session:"), BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(pollList);
        listScroll.setPreferredSize(new Dimension(380, 160));
        listPanel.add(listScroll, BorderLayout.CENTER);
        JPanel pb = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton startPoll = new JButton("Start poll");
        startPoll.addActionListener(e -> pollCommand(Protocol.START_POLL));
        JButton stopPoll = new JButton("Stop poll");
        stopPoll.addActionListener(e -> pollCommand(Protocol.STOP_POLL));
        pb.add(startPoll);
        pb.add(stopPoll);
        listPanel.add(pb, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.add(editor, BorderLayout.NORTH);
        left.add(listPanel, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout());
        pollTimer.setFont(pollTimer.getFont().deriveFont(Font.BOLD, 16f));
        right.add(pollTimer, BorderLayout.NORTH);
        right.add(chart, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }

    private JPanel exportPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 20));
        JButton att = new JButton("Export attendance CSV");
        att.addActionListener(e -> export(Protocol.EXPORT_ATTENDANCE, "attendance"));
        JButton polls = new JButton("Export poll CSV");
        polls.addActionListener(e -> export(Protocol.EXPORT_POLLS, "polls"));
        progress.setStringPainted(true);
        p.add(att);
        p.add(polls);
        p.add(progress);
        p.add(exportStatus);
        return p;
    }

    // ------------------------------------------------------------------ actions

    private void connectInBackground() {
        new Thread(() -> {
            try {
                client.connect(token);
            } catch (Exception e) {
                onConnectionStatus("Cannot connect: " + ClassroomClient.friendly(e), false);
            }
        }, "connect").start();
    }

    private void createPoll() {
        List<String> opts = new ArrayList<>();
        for (String line : optionsArea.getText().split("\n")) {
            String o = Protocol.sanitize(line);
            if (!o.isEmpty()) {
                opts.add(o);
            }
        }
        client.send(Protocol.join(Protocol.CREATE_POLL, pollTime.getValue(), String.join(Protocol.LIST_SEP, opts),
                Protocol.sanitizeFreeText(questionField.getText())));
    }

    private void pollCommand(String cmd) {
        String sel = pollList.getSelectedValue();
        if (sel == null) {
            appendLog("Select a poll in the list first.");
            return;
        }
        client.send(Protocol.join(cmd, sel.substring(0, sel.indexOf(':'))));
    }

    private void export(String type, String name) {
        if (joinCode == null) {
            exportStatus.setText("Create a session first.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(joinCode + "_" + name + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        progress.setValue(0);
        exportStatus.setText("Downloading...");
        // Member 4's ExportClient runs the download on a SwingWorker (background thread)
        exportClient.downloadInBackground(login.host(), token, type, joinCode, chooser.getSelectedFile(), progress,
                msg -> exportStatus.setText(msg));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (attClosesAt > now) {
            long s = (attClosesAt - now) / 1000;
            attCloseTimer.setText(String.format("Attendance closes in %d:%02d", s / 60, s % 60));
            long c = Math.max(0, (codeChangesAt - now + 999) / 1000);
            attCodeTimer.setText("New code in " + c + " s");
        }
        if (pollEndsAt > now) {
            pollTimer.setText("Time left: " + ((pollEndsAt - now + 999) / 1000) + " s");
        }
    }

    // ------------------------------------------------------------------ server messages

    @Override
    public void onLine(String line) {
        SwingUtilities.invokeLater(() -> handle(line)); // reader thread -> Swing thread
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
            case Protocol.OK -> appendLog("Logged in as " + (p.length > 2 ? p[2] : "?"));
            case Protocol.SESSION -> {
                String[] s = Protocol.split(line, 4);
                joinCode = s[1];
                joinCodeLabel.setText(s[1]);
                sessionLabel.setText(s[2] + " - " + s[3]);
                appendLog("Session " + s[1] + " is live. Students can now discover it.");
            }
            case Protocol.ATT_CODE -> {
                attCodeLabel.setText(p[1]);
                codeChangesAt = System.currentTimeMillis() + Protocol.parseIntOr(p[2], 30) * 1000L;
            }
            case Protocol.ATT_OPEN -> attClosesAt = Protocol.parseLongOr(p[1], 0);
            case Protocol.ATT_CLOSED -> {
                attClosesAt = 0;
                attCodeLabel.setText("------");
                attCodeTimer.setText(" ");
                attCloseTimer.setText("Attendance is closed");
            }
            case Protocol.ATT_COUNT -> attCount.setText(p[1] + " / " + p[2]);
            case Protocol.POLL_LIST -> {
                pollModel.clear();
                String body = line.substring(Protocol.POLL_LIST.length() + 1);
                if (!body.isEmpty()) {
                    // items look like "P1:[OPEN] question"; split only before the next "P<number>:"
                    for (String item : body.split(",(?=P\\d+:)")) {
                        pollModel.addElement(item);
                    }
                }
            }
            case Protocol.POLL_START -> {
                String[] s = Protocol.split(line, 5);
                chart.setPoll(s[1] + ": " + s[4], Arrays.asList(s[3].split(Protocol.LIST_SEP)));
                pollEndsAt = System.currentTimeMillis() + Protocol.parseIntOr(s[2], 0) * 1000L;
                appendLog("Poll " + s[1] + " started");
            }
            case Protocol.POLL_UPDATE -> chart.setCounts(Protocol.parseCounts(p[2]), Protocol.parseIntOr(p[3], 0));
            case Protocol.POLL_END -> {
                chart.setCounts(Protocol.parseCounts(p[2]), Protocol.parseIntOr(p[3], 0));
                pollEndsAt = 0;
                pollTimer.setText("Poll " + p[1] + " closed - final result");
                appendLog("Poll " + p[1] + " closed with " + p[3] + " votes");
            }
            case Protocol.SYS -> {
                String text = line.substring(4);
                appendLog(text);
                if (text.startsWith("WARNING")) {
                    warnings.append(LocalTime.now().format(HMS) + "  " + text + "\n");
                }
                if (text.startsWith("Session ended")) {
                    joinCode = null;
                    joinCodeLabel.setText("------");
                    sessionLabel.setText("Session ended (CSV files auto-saved on the server in exports/)");
                    pollModel.clear();
                }
            }
            case Protocol.ERR -> appendLog("ERROR: " + (p.length > 1 ? line.substring(4) : line));
            default -> { }
        }
    }

    private void appendLog(String text) {
        log.append(LocalTime.now().format(HMS) + "  " + text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static JLabel centered(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setAlignmentX(0.5f);
        return l;
    }

    private static JLabel big(String text, float size) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(new Font(Font.MONOSPACED, Font.BOLD, (int) size));
        return l;
    }
}
