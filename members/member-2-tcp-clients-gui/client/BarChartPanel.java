// Owner: Member 2, Swing GUI | Lecture topic: Custom painting with Java2D (paintComponent)
package com.classpulse.client;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JPanel;

/** Horizontal bar chart of poll votes, drawn by hand with Java2D (no chart library). */
public class BarChartPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Color[] COLORS = {
        new Color(0x2E86DE), new Color(0x10AC84), new Color(0xEE5253),
        new Color(0xFF9F43), new Color(0x8854D0), new Color(0x0ABDE3)
    };

    private String title = "No poll yet";
    private List<String> labels = new ArrayList<>();
    private int[] counts = new int[0];
    private int total;

    public BarChartPanel() {
        setPreferredSize(new Dimension(460, 260));
        setBackground(Color.WHITE);
    }

    /** Must be called on the Swing thread. */
    public void setPoll(String title, List<String> labels) {
        this.title = title;
        this.labels = new ArrayList<>(labels);
        this.counts = new int[labels.size()];
        this.total = 0;
        repaint();
    }

    public void setCounts(int[] newCounts, int newTotal) {
        if (newCounts.length == labels.size()) {
            this.counts = newCounts.clone();
            this.total = newTotal;
            repaint(); // asks Swing to call paintComponent soon
        }
    }

    public void setTitle(String newTitle) {
        this.title = newTitle;
        repaint();
    }

    public void clear(String message) {
        setPoll(message, new ArrayList<>());
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int pad = 12;

        g.setColor(Color.DARK_GRAY);
        g.setFont(getFont().deriveFont(Font.BOLD, 15f));
        g.drawString(title + (labels.isEmpty() ? "" : "   (" + total + " votes)"), pad, pad + 14);
        if (labels.isEmpty()) {
            return;
        }

        g.setFont(getFont().deriveFont(Font.PLAIN, 13f));
        FontMetrics fm = g.getFontMetrics();
        int labelWidth = 0;
        for (String l : labels) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(l));
        }
        labelWidth = Math.min(labelWidth, w / 3);

        int top = pad + 32;
        int rowH = Math.max(24, Math.min(44, (getHeight() - top - pad) / labels.size()));
        int barX = pad + labelWidth + 10;
        int maxBar = Math.max(10, w - barX - 110);
        int max = 1;
        for (int c : counts) {
            max = Math.max(max, c);
        }

        for (int i = 0; i < labels.size(); i++) {
            int y = top + i * rowH;
            g.setColor(Color.BLACK);
            g.drawString(shorten(labels.get(i), fm, labelWidth), pad, y + rowH / 2 + 5);
            int barLen = (int) ((long) maxBar * counts[i] / max);
            g.setColor(COLORS[i % COLORS.length]);
            g.fillRoundRect(barX, y + 4, Math.max(barLen, 2), rowH - 8, 8, 8);
            double pct = total == 0 ? 0 : 100.0 * counts[i] / total;
            g.setColor(Color.DARK_GRAY);
            g.drawString(counts[i] + "  (" + String.format(Locale.ROOT, "%.0f", pct) + "%)", barX + barLen + 8, y + rowH / 2 + 5);
        }
    }

    private static String shorten(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        while (s.length() > 1 && fm.stringWidth(s + "...") > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "...";
    }
}
