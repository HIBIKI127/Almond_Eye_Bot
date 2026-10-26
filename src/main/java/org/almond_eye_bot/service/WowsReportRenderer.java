package org.almond_eye_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Deterministic local report rendering. No AI, browser, external image, or disk cache. */
@Service
public class WowsReportRenderer {
    @Value("${wows-stats.image-enabled:true}") private boolean enabled = true;
    private static final int WIDTH = 1200, PADDING = 64;
    private static final Color BACKGROUND = new Color(14, 24, 40), PANEL = new Color(24, 39, 59);
    private static final Color WHITE = new Color(235, 242, 250), MUTED = new Color(161, 180, 201), ACCENT = new Color(76, 220, 190);

    public boolean isEnabled() { return enabled; }

    public byte[] render(WowsStatsService.Result result) throws IOException {
        if (!result.success() || result.report() == null || result.report().length() > 10000)
            throw new IOException("No renderable report");
        String family = fontFamily();
        var canvas = new BufferedImage(WIDTH, 4096, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        int bottom;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND); g.fillRect(0, 0, WIDTH, canvas.getHeight());
            g.setColor(ACCENT); g.fillRoundRect(PADDING, 54, 6, 34, 6, 6);
            font(g, family, 22, Font.BOLD, ACCENT); g.drawString("ALMOND EYE  /  WORLD OF WARSHIPS", PADDING + 22, 80);
            String[] lines = result.report().split("\\R");
            font(g, family, 38, Font.BOLD, WHITE);
            int y = text(g, lines[0], PADDING, 143, WIDTH - 2 * PADDING, 51) + 15;
            if (lines.length > 1) {
                font(g, family, 28, Font.PLAIN, ACCENT);
                y = text(g, lines[1], PADDING, y, WIDTH - 2 * PADDING, 40) + 25;
            }
            List<String[]> metrics = new ArrayList<>();
            List<String> context = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (int i = 2; i < lines.length; i++) {
                if (lines[i].contains("｜")) {
                    for (String part : lines[i].split("｜")) {
                        String[] pair = part.strip().split("：", 2);
                        if (pair.length == 2) metrics.add(pair);
                    }
                } else if (!lines[i].isBlank()) {
                    if (metrics.isEmpty()) context.add(lines[i].replace("以下为", "统计口径为"));
                    else notes.add(lines[i]);
                }
            }
            font(g, family, 23, Font.PLAIN, MUTED);
            for (String line : context) y = text(g, line, PADDING, y, WIDTH - 2 * PADDING, 34) + 10;
            y += 12;
            int cardWidth = (WIDTH - 2 * PADDING - 32) / 3;
            for (int i = 0; i < metrics.size(); i++) {
                int x = PADDING + (i % 3) * (cardWidth + 16), top = y + (i / 3) * 144;
                g.setColor(PANEL); g.fillRoundRect(x, top, cardWidth, 126, 20, 20);
                font(g, family, 22, Font.PLAIN, MUTED); g.drawString(metrics.get(i)[0], x + 20, top + 36);
                String value = metrics.get(i)[1].replace("（含高级账号加成）", "");
                int size = 34;
                while (size > 17 && new Font(family, Font.BOLD, size).getStringBounds(value, g.getFontRenderContext()).getWidth() > cardWidth - 40) size--;
                font(g, family, size, Font.BOLD, WHITE); text(g, value, x + 20, top + 83, cardWidth - 40, 24);
            }
            y += ((metrics.size() + 2) / 3) * 144 + 22;
            g.setColor(new Color(48, 66, 85)); g.drawLine(PADDING, y, WIDTH - PADDING, y);
            y += 38;
            font(g, family, 23, Font.PLAIN, MUTED);
            for (String line : notes) y = text(g, line, PADDING, y, WIDTH - 2 * PADDING, 34) + 12;
            if (!metrics.isEmpty()) y = text(g, "经验采用官方值，可能包含高级账号加成；模式与时间范围以上述说明为准。", PADDING, y + 4, WIDTH - 2 * PADDING, 34) + 18;
            font(g, family, 20, Font.PLAIN, ACCENT);
            y = text(g, "战绩报告 · 数据与点评分开发送", PADDING, y + 10, WIDTH - 2 * PADDING, 30);
            bottom = y + 30;
            if (bottom > canvas.getHeight()) throw new IOException("Report is too tall");
        } finally { g.dispose(); }
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(canvas.getSubimage(0, 0, WIDTH, bottom), "png", output)) throw new IOException("PNG encoder unavailable");
        return output.toByteArray();
    }

    private static void font(Graphics2D g, String family, int size, int style, Color color) {
        g.setFont(new Font(family, style, size)); g.setColor(color);
    }
    private static String fontFamily() throws IOException {
        Set<String> available = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : List.of("Microsoft YaHei", "微软雅黑", "Noto Sans CJK SC", "Noto Sans SC", "Source Han Sans SC", "SimSun", "Dialog")) {
            if (available.contains(family) && new Font(family, Font.PLAIN, 24).canDisplayUpTo("战舰世界总体战绩近期伤害") == -1) return family;
        }
        throw new IOException("Chinese font unavailable");
    }
    private static int text(Graphics2D g, String text, int x, int y, int width, int lineHeight) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int cp : text.codePoints().toArray()) {
            String next = new String(Character.toChars(cp));
            if (!line.isEmpty() && g.getFontMetrics().stringWidth(line + next) > width) {
                g.drawString(line.toString(), x, y); line.setLength(0); y += lineHeight;
            }
            if (y > 4000) throw new IOException("Report is too tall");
            line.append(next);
        }
        if (!line.isEmpty()) g.drawString(line.toString(), x, y);
        return y + lineHeight;
    }
}
