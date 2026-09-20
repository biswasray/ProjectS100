/*
 * S100 shell - modal popups: notes, Yes/No questions and option menus.
 *
 * A popup sits above the current screen, takes all keys and replaces the
 * soft key labels while it is shown. The Nokia "Options" menu is a MENU
 * popup anchored at the bottom left; questions and notes are centred.
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import javax.microedition.lcdui.Graphics;

class Popup {
    /** Result callback; MENU: option index, CONFIRM: 1 yes / 0 no, INFO: 0. */
    interface Listener {
        void onResult(int result);
    }

    static final int INFO = 0;
    static final int CONFIRM = 1;
    static final int MENU = 2;
    /** A "please wait" note that keys cannot dismiss; closed by the caller. */
    static final int WAIT = 3;

    Shell shell;
    final int type;
    String title;
    String text;
    String[] options;
    int selected;
    /** MENU: index drawn with a radio mark (current setting), or -1. */
    int checked = -1;
    Listener listener;
    private String leftLabel, rightLabel;

    private Popup(int type) {
        this.type = type;
    }

    static Popup info(String text, int autoCloseMs) {
        Popup p = new Popup(INFO);
        p.text = text;
        p.rightLabel = "OK";
        if (autoCloseMs > 0) {
            p.autoClose = autoCloseMs;
        }
        return p;
    }

    static Popup wait(String text) {
        Popup p = new Popup(WAIT);
        p.text = text;
        return p;
    }

    static Popup confirm(String text, Listener l) {
        Popup p = new Popup(CONFIRM);
        p.text = text;
        p.listener = l;
        p.leftLabel = "Yes";
        p.rightLabel = "No";
        return p;
    }

    static Popup menu(String title, String[] options, Listener l) {
        Popup p = new Popup(MENU);
        p.title = title;
        p.options = options;
        p.listener = l;
        p.leftLabel = "Select";
        p.rightLabel = "Back";
        return p;
    }

    static Popup menu(String title, Vector options, Listener l) {
        String[] o = new String[options.size()];
        options.copyInto(o);
        return menu(title, o, l);
    }

    /** Menu with the current value marked; selection starts on it. */
    static Popup choice(String title, String[] options, int current, Listener l) {
        Popup p = menu(title, options, l);
        p.checked = current;
        p.selected = Math.max(0, current);
        return p;
    }

    Popup labels(String left, String right) {
        leftLabel = left;
        rightLabel = right;
        return this;
    }

    private int autoClose;
    private boolean armed;

    String softLeft() { return leftLabel; }
    String softMid() { return null; }
    String softRight() { return rightLabel; }

    private void finish(int result) {
        shell.closePopup();
        if (listener != null) {
            listener.onResult(result);
        }
    }

    void key(int k) {
        switch (type) {
        case WAIT:
            break;
        case INFO:
            finish(0);
            break;
        case CONFIRM:
            if (k == Keymap.SOFT_L || k == Keymap.SELECT) {
                finish(1);
            } else if (k == Keymap.SOFT_R || k == Keymap.END) {
                finish(0);
            }
            break;
        case MENU:
            if (k == Keymap.UP) {
                selected = (selected + options.length - 1) % options.length;
                shell.repaint();
            } else if (k == Keymap.DOWN) {
                selected = (selected + 1) % options.length;
                shell.repaint();
            } else if (k == Keymap.SOFT_L || k == Keymap.SELECT) {
                finish(selected);
            } else if (k == Keymap.SOFT_R || k == Keymap.END) {
                shell.closePopup();
            } else if (Keymap.isDigit(k) && k != '0') {
                int i = k - '1';
                if (i < options.length) {
                    selected = i;
                    finish(i);
                }
            }
            break;
        }
    }

    void keyRepeat(int k) {
        if (type == MENU && (k == Keymap.UP || k == Keymap.DOWN)) {
            key(k);
        }
    }

    void keyLong(int k) { }

    void paint(Graphics g, int w, int bottom) {
        if (autoClose > 0 && !armed) {
            armed = true;
            final Popup me = this;
            shell.later(new Runnable() {
                public void run() {
                    if (shell.popup() == me) {
                        finish(0);
                    }
                }
            }, autoClose);
        }
        if (type == MENU) {
            paintMenu(g, w, bottom);
        } else {
            paintNote(g, w, bottom);
        }
    }

    private void frame(Graphics g, int x, int y, int bw, int bh) {
        g.setColor(0x000000);
        g.fillRoundRect(x + 2, y + 2, bw, bh, 6, 6);     // drop shadow
        g.setColor(Theme.C_POPUP_BG);
        g.fillRoundRect(x, y, bw, bh, 6, 6);
        g.setColor(Theme.C_FRAME);
        g.drawRoundRect(x, y, bw - 1, bh - 1, 6, 6);
    }

    private void paintNote(Graphics g, int w, int bottom) {
        int bw = w - 24;
        int tw = bw - 16;
        String[] lines = Theme.wrap(text, tw);
        int bh = lines.length * Theme.FONT_H + 20;
        int x = 12;
        int y = (bottom - bh) / 2;
        frame(g, x, y, bw, bh);
        g.setColor(Theme.C_TEXT);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], x + bw / 2, y + 10 + i * Theme.FONT_H,
                         Graphics.TOP | Graphics.HCENTER);
        }
    }

    private static final int ROW = 22;
    private static final int MAX_ROWS = 8;

    private void paintMenu(Graphics g, int w, int bottom) {
        int rows = Math.min(options.length, MAX_ROWS);
        int titleH = (title == null) ? 0 : Theme.TITLE_H;
        int bh = rows * ROW + titleH + 8;
        int bw = w - 30;
        int x = 4;
        int y = bottom - bh - 2;
        frame(g, x, y, bw, bh);
        int cy = y + 4;
        if (title != null) {
            g.setColor(Theme.C_TITLE_TOP);
            Theme.bold(g, Theme.fit(title, bw - 16), x + 8, cy + 4,
                       Graphics.TOP | Graphics.LEFT);
            g.setColor(Theme.C_SOFT_LINE);
            g.drawLine(x + 6, cy + titleH - 2, x + bw - 6, cy + titleH - 2);
            cy += titleH;
        }
        int first = 0;
        if (selected >= rows) {
            first = selected - rows + 1;
        }
        int textW = bw - 24 - (options.length > rows ? 6 : 0);
        for (int i = 0; i < rows; i++) {
            int idx = first + i;
            int ry = cy + i * ROW;
            if (idx == selected) {
                Theme.selection(g, x + 4, ry, bw - 8, ROW);
                g.setColor(Theme.C_SEL_TEXT);
            } else {
                g.setColor(Theme.C_TEXT);
            }
            int tx = x + 10;
            if (checked >= 0) {
                g.drawArc(tx, ry + 5, 10, 10, 0, 360);
                if (idx == checked) {
                    g.fillArc(tx + 3, ry + 8, 5, 5, 0, 360);
                }
                tx += 16;
            }
            g.drawString(Theme.fit(options[idx], textW), tx, ry + (ROW - Theme.FONT_H) / 2,
                         Graphics.TOP | Graphics.LEFT);
        }
        Theme.scrollBar(g, x + bw - 8, cy, rows * ROW, options.length, rows, first);
    }
}
