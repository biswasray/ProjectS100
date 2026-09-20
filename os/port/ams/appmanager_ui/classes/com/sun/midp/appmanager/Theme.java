/*
 * S100 shell - look & feel constants and drawing helpers.
 *
 * Part of the JioPhone phoneME port (os/port/ams). The linux_fb putpixel
 * backend has exactly one bitmap font (9x14, every face/style/size), so
 * "bold" is drawn twice with a 1px offset and the big idle clock uses its
 * own digit bitmaps (see bigDigit()).
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

final class Theme {
    /** Screen geometry (constants_jiophone.xml: 240x320). */
    static final int W = 240;
    static final int H = 320;
    /** Status bar (top), title bar, soft key bar (bottom), list row. */
    static final int STATUS_H = 18;
    static final int TITLE_H = 22;
    static final int SOFT_H = 22;
    static final int ROW_H = 24;
    static final int MARGIN = 4;

    /** Nokia-blue palette. */
    static final int C_TITLE_TOP = 0x1E4FA8;
    static final int C_TITLE_BOT = 0x3C7BD8;
    static final int C_TITLE_TEXT = 0xFFFFFF;
    static final int C_BG = 0xFFFFFF;
    static final int C_TEXT = 0x101010;
    static final int C_TEXT_DIM = 0x707880;
    static final int C_SEL = 0x2A63C8;
    static final int C_SEL_LIGHT = 0x5B8EE0;
    static final int C_SEL_TEXT = 0xFFFFFF;
    static final int C_SOFT_BG = 0xEEF2F8;
    static final int C_SOFT_LINE = 0xB8C4D6;
    static final int C_SOFT_TEXT = 0x101010;
    static final int C_STATUS_BG = 0xEEF2F8;
    static final int C_FRAME = 0x8FA3C4;
    static final int C_POPUP_BG = 0xF7F9FC;
    static final int C_SCROLL = 0xC4CEDD;
    static final int C_SCROLL_THUMB = 0x2A63C8;
    static final int C_WALL_TOP = 0x0B2A6E;
    static final int C_WALL_MID = 0x2F6FD6;
    static final int C_WALL_BOT = 0x9CC5F5;
    static final int C_HOME_TEXT = 0xFFFFFF;
    static final int C_HOME_SHADOW = 0x0A1F4A;

    static final Font FONT = Font.getDefaultFont();
    static final int FONT_H = FONT.getHeight();
    /** The port font is monospaced; cache one advance for layout maths. */
    static final int CH_W = FONT.charWidth('W');

    private Theme() { }

    /** Vertical gradient between two RGB colours. */
    static void gradient(Graphics g, int x, int y, int w, int h,
                         int top, int bottom) {
        if (h <= 0) {
            return;
        }
        int r0 = (top >> 16) & 0xff, g0 = (top >> 8) & 0xff, b0 = top & 0xff;
        int r1 = (bottom >> 16) & 0xff, g1 = (bottom >> 8) & 0xff,
            b1 = bottom & 0xff;
        for (int i = 0; i < h; i++) {
            int r = r0 + (r1 - r0) * i / (h - 1 > 0 ? h - 1 : 1);
            int gg = g0 + (g1 - g0) * i / (h - 1 > 0 ? h - 1 : 1);
            int b = b0 + (b1 - b0) * i / (h - 1 > 0 ? h - 1 : 1);
            g.setColor((r << 16) | (gg << 8) | b);
            g.drawLine(x, y + i, x + w - 1, y + i);
        }
    }

    /** Text drawn twice, one pixel apart: the only bold this font has. */
    static void bold(Graphics g, String s, int x, int y, int anchor) {
        g.drawString(s, x, y, anchor);
        g.drawString(s, x + 1, y, anchor);
    }

    /** Text with a 1px drop shadow (idle screen over the wallpaper). */
    static void shadowText(Graphics g, String s, int x, int y, int anchor,
                           int color, int shadow) {
        g.setColor(shadow);
        g.drawString(s, x + 1, y + 1, anchor);
        g.setColor(color);
        g.drawString(s, x, y, anchor);
    }

    /** Cut a string with an ellipsis so it fits in w pixels. */
    static String fit(String s, int w) {
        if (s == null) {
            return "";
        }
        if (FONT.stringWidth(s) <= w) {
            return s;
        }
        int n = s.length();
        while (n > 0 && FONT.stringWidth(s.substring(0, n) + "\u2026") > w) {
            n--;
        }
        return s.substring(0, n) + "\u2026";
    }

    /** Standard title bar: centred bold white text, optional right corner text. */
    static void titleBar(Graphics g, int y, String title, String right) {
        gradient(g, 0, y, W, TITLE_H, C_TITLE_TOP, C_TITLE_BOT);
        g.setColor(C_TITLE_TEXT);
        int ty = y + (TITLE_H - FONT_H) / 2;
        int avail = W - 2 * MARGIN;
        if (right != null) {
            g.drawString(right, W - MARGIN, ty, Graphics.TOP | Graphics.RIGHT);
            avail -= 2 * (FONT.stringWidth(right) + MARGIN);
        }
        bold(g, fit(title, avail), W / 2, ty, Graphics.TOP | Graphics.HCENTER);
    }

    /** Selection highlight bar used by lists and grids. */
    static void selection(Graphics g, int x, int y, int w, int h) {
        gradient(g, x, y, w, h, C_SEL_LIGHT, C_SEL);
        g.setColor(C_SEL);
        g.drawRect(x, y, w - 1, h - 1);
    }

    /** Vertical scroll bar; total/visible/first are item counts. */
    static void scrollBar(Graphics g, int x, int y, int h, int total,
                          int visible, int first) {
        if (total <= visible) {
            return;
        }
        g.setColor(C_SCROLL);
        g.fillRect(x, y, 4, h);
        int th = Math.max(8, h * visible / total);
        int ty = y + (h - th) * first / (total - visible);
        g.setColor(C_SCROLL_THUMB);
        g.fillRect(x, ty, 4, th);
    }

    /** Wraps text into lines no wider than w pixels (word wrap, '\n' aware). */
    static String[] wrap(String text, int w) {
        java.util.Vector lines = new java.util.Vector();
        if (text == null) {
            text = "";
        }
        int start = 0;
        int len = text.length();
        while (start <= len) {
            int nl = text.indexOf('\n', start);
            String para = (nl < 0) ? text.substring(start) : text.substring(start, nl);
            wrapPara(para, w, lines);
            if (nl < 0) {
                break;
            }
            start = nl + 1;
            if (start == len) {
                lines.addElement("");
                break;
            }
        }
        String[] out = new String[lines.size()];
        lines.copyInto(out);
        return out;
    }

    private static void wrapPara(String para, int w, java.util.Vector out) {
        if (para.length() == 0) {
            out.addElement("");
            return;
        }
        int pos = 0;
        int len = para.length();
        while (pos < len) {
            int end = pos;
            int lastSpace = -1;
            while (end < len
                   && FONT.substringWidth(para, pos, end - pos + 1) <= w) {
                if (para.charAt(end) == ' ') {
                    lastSpace = end;
                }
                end++;
            }
            if (end >= len) {
                out.addElement(para.substring(pos));
                break;
            }
            if (end == pos) {
                end = pos + 1;           // never fits: emit one char
            } else if (lastSpace > pos) {
                end = lastSpace + 1;
            }
            out.addElement(para.substring(pos, end));
            pos = end;
            while (pos < len && para.charAt(pos) == ' ') {
                pos++;
            }
        }
    }

    /* ---------------- big digits for the idle clock ---------------- */

    /** 5x7 glyphs, one row per int bit-row (bit4 = leftmost column). */
    private static final int[][] DIGITS = {
        {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E},   // 0
        {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E},   // 1
        {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F},   // 2
        {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E},   // 3
        {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02},   // 4
        {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E},   // 5
        {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E},   // 6
        {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08},   // 7
        {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E},   // 8
        {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C},   // 9
    };
    private static final int[] COLON = {0x00, 0x04, 0x04, 0x00, 0x04, 0x04, 0x00};

    /** Width of one big glyph cell (5 columns + 1 gap) at the given scale. */
    static int bigWidth(int scale) {
        return 6 * scale;
    }

    static int bigHeight(int scale) {
        return 7 * scale;
    }

    /** Draws '0'..'9' or ':' scaled by `scale` with the current colour. */
    static void bigDigit(Graphics g, char c, int x, int y, int scale) {
        int[] rows;
        if (c >= '0' && c <= '9') {
            rows = DIGITS[c - '0'];
        } else if (c == ':') {
            rows = COLON;
        } else {
            return;
        }
        for (int r = 0; r < 7; r++) {
            int bits = rows[r];
            int col = 0;
            while (col < 5) {
                if ((bits & (0x10 >> col)) != 0) {
                    int run = 1;
                    while (col + run < 5 && (bits & (0x10 >> (col + run))) != 0) {
                        run++;
                    }
                    g.fillRect(x + col * scale, y + r * scale, run * scale, scale);
                    col += run;
                } else {
                    col++;
                }
            }
        }
    }

    /** Draws a whole string of big digits/colons, returns the width used. */
    static int bigString(Graphics g, String s, int x, int y, int scale) {
        int cx = x;
        for (int i = 0; i < s.length(); i++) {
            bigDigit(g, s.charAt(i), cx, y, scale);
            cx += bigWidth(scale);
        }
        return cx - x;
    }

    /** Draws an image centred in a box (no scaling available in MIDP). */
    static void imageCentered(Graphics g, Image img, int x, int y, int w, int h) {
        if (img == null) {
            return;
        }
        g.drawImage(img, x + (w - img.getWidth()) / 2,
                    y + (h - img.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
    }

    static String two(int v) {
        return (v < 10 ? "0" : "") + v;
    }
}
