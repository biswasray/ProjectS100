/*
 * S100 shell - read-only text screen (messages, notes, phone info).
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Graphics;

class TextViewScreen extends Screen {
    private String text;
    private String[] lines;
    private int first;
    private int rows = 1;
    private String[] options;

    TextViewScreen(String title, String text) {
        super(title);
        this.text = text;
    }

    void setText(String t) {
        text = t;
        lines = null;
        first = 0;
        repaint();
    }

    /** Left soft key "Options" entries; option(i) is called on choice. */
    TextViewScreen options(String[] opts) {
        options = opts;
        return this;
    }

    void option(int index) { }

    String softLeft() { return options == null ? null : "Options"; }

    void paint(Graphics g, int x, int y, int w, int h) {
        if (lines == null) {
            lines = Theme.wrap(text, w - 2 * Theme.MARGIN - 10);
        }
        int lineH = Theme.FONT_H + 2;
        rows = Math.max(1, (h - 6) / lineH);
        if (first > lines.length - rows) {
            first = Math.max(0, lines.length - rows);
        }
        g.setColor(Theme.C_TEXT);
        for (int i = 0; i < rows && first + i < lines.length; i++) {
            g.drawString(lines[first + i], x + Theme.MARGIN + 2, y + 3 + i * lineH,
                         Graphics.TOP | Graphics.LEFT);
        }
        Theme.scrollBar(g, x + w - 6, y, h, lines.length, rows, first);
    }

    boolean key(int k) {
        switch (k) {
        case Keymap.UP:
            if (first > 0) {
                first--;
                repaint();
            }
            return true;
        case Keymap.DOWN:
            if (lines != null && first < lines.length - rows) {
                first++;
                repaint();
            }
            return true;
        case Keymap.SOFT_L:
        case Keymap.SELECT:
            if (options != null) {
                shell.showPopup(Popup.menu("Options", options, new Popup.Listener() {
                    public void onResult(int r) {
                        option(r);
                    }
                }));
            }
            return true;
        default:
            return false;
        }
    }

    void keyRepeat(int k) {
        if (k == Keymap.UP || k == Keymap.DOWN) {
            key(k);
        }
    }
}
