/*
 * S100 shell - Nokia style multi-tap text editor on the 12-key keypad.
 *
 *   2..9   letters (tap repeatedly within a second to cycle), long press
 *          inserts the digit itself
 *   1      punctuation cycle          0   space (long press: '0')
 *   #      case: Abc -> abc -> ABC -> 123 -> Abc
 *   *      symbols cycle (number mode: * + p w)
 *   left/right  move the cursor       up/down  line up/down
 *   right soft  Clear (long press: clear all); Back when empty
 *   left soft / centre  OK -> Listener.onText()
 */

package com.sun.midp.appmanager;

import java.util.TimerTask;
import javax.microedition.lcdui.Graphics;

class TextInputScreen extends Screen {
    interface Listener {
        void onText(String text);
    }

    static final int TEXT = 0;
    static final int NUMBER = 1;

    private static final String[] LETTERS = {
        " 0", ".,?!'\"-()@/:_;+&%*=<>$\u20ac\u00a3[]{}\\~^`|#",
        "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"
    };
    private static final String SYMBOLS = "*+-/=@#$%&";
    private static final String NUM_STAR = "*+pw";
    private static final int MULTITAP_MS = 1000;

    private static final int CASE_ABC = 0;    // Abc: capital after a sentence start
    private static final int CASE_LOWER = 1;
    private static final int CASE_UPPER = 2;
    private static final int CASE_NUM = 3;
    private static final String[] CASE_LABELS = {"Abc", "abc", "ABC", "123"};

    private final StringBuffer buf = new StringBuffer();
    private final int mode;
    private final int maxLen;
    private final Listener listener;
    private final String okLabel;
    private int cursor;
    private int caseMode;

    private int pendingKey = Keymap.NONE;
    private int pendingIdx;
    private int pendingSeq;

    private boolean cursorOn = true;
    private TimerTask blink;
    private int firstLine;

    TextInputScreen(String title, String initial, int mode, int maxLen,
                    String okLabel, Listener l) {
        super(title);
        this.mode = mode;
        this.maxLen = maxLen;
        this.listener = l;
        this.okLabel = okLabel;
        if (initial != null) {
            buf.append(initial);
        }
        cursor = buf.length();
        caseMode = (mode == NUMBER) ? CASE_NUM : CASE_ABC;
    }

    String text() {
        return buf.toString();
    }

    String titleRight() { return mode == TEXT ? CASE_LABELS[caseMode] : null; }
    String softLeft() { return okLabel; }
    String softRight() { return buf.length() > 0 ? "Clear" : "Back"; }

    void onShow() {
        if (blink == null) {
            blink = shell.every(new Runnable() {
                public void run() {
                    cursorOn = !cursorOn;
                    repaint();
                }
            }, 500);
        }
    }

    void onHide() {
        if (blink != null) {
            blink.cancel();
            blink = null;
        }
        commitPending();
    }

    /* ---------------- editing ---------------- */

    private boolean upperNext() {
        if (caseMode == CASE_UPPER) {
            return true;
        }
        if (caseMode != CASE_ABC) {
            return false;
        }
        // sentence start: beginning of text, or after ". ", "! ", "? "
        int i = cursor - 1;
        while (i >= 0 && buf.charAt(i) == ' ') {
            i--;
        }
        if (i < 0) {
            return true;
        }
        char c = buf.charAt(i);
        return (c == '.' || c == '!' || c == '?') && i < cursor - 1;
    }

    private void insert(char c) {
        if (buf.length() >= maxLen) {
            return;
        }
        buf.insert(cursor, c);
        cursor++;
    }

    private void commitPending() {
        pendingKey = Keymap.NONE;
        pendingSeq++;
    }

    private void armTimeout() {
        final int seq = ++pendingSeq;
        shell.later(new Runnable() {
            public void run() {
                if (seq == pendingSeq && pendingKey != Keymap.NONE) {
                    pendingKey = Keymap.NONE;
                    repaint();
                }
            }
        }, MULTITAP_MS);
    }

    /** Cycles `alphabet` on `key` (multi-tap), replacing the pending char. */
    private void tap(int key, String alphabet) {
        if (pendingKey == key && cursor > 0) {
            pendingIdx = (pendingIdx + 1) % alphabet.length();
            char c = alphabet.charAt(pendingIdx);
            if (Character.isLowerCase(c) && upperPending) {
                c = Character.toUpperCase(c);
            }
            buf.setCharAt(cursor - 1, c);
        } else {
            commitPending();
            if (buf.length() >= maxLen) {
                return;
            }
            pendingIdx = 0;
            char c = alphabet.charAt(0);
            upperPending = Character.isLowerCase(c) && upperNext();
            if (upperPending) {
                c = Character.toUpperCase(c);
            }
            insert(c);
            pendingKey = key;
        }
        armTimeout();
        repaint();
    }

    private boolean upperPending;

    private void backspace() {
        commitPending();
        if (cursor > 0) {
            buf.deleteCharAt(cursor - 1);
            cursor--;
            repaint();
        }
    }

    private void done() {
        commitPending();
        String t = buf.toString();
        shell.pop();
        if (listener != null) {
            listener.onText(t);
        }
    }

    boolean key(int k) {
        switch (k) {
        case Keymap.SOFT_L:
        case Keymap.SELECT:
            done();
            return true;
        case Keymap.SOFT_R:
        case Keymap.CLEAR:
            if (buf.length() == 0) {
                commitPending();
                return false;            // shell default: back()
            }
            backspace();
            return true;
        case Keymap.LEFT:
            commitPending();
            if (cursor > 0) {
                cursor--;
            }
            repaint();
            return true;
        case Keymap.RIGHT:
            commitPending();
            if (cursor < buf.length()) {
                cursor++;
            }
            repaint();
            return true;
        case Keymap.UP:
        case Keymap.DOWN: {
            commitPending();
            int cpl = charsPerLine();
            int n = (k == Keymap.UP) ? -cpl : cpl;
            cursor = Math.max(0, Math.min(buf.length(), cursor + n));
            repaint();
            return true;
        }
        case Keymap.POUND:
            if (mode == NUMBER) {
                commitPending();
                insert('#');
            } else {
                commitPending();
                caseMode = (caseMode + 1) % 4;
            }
            repaint();
            return true;
        case Keymap.STAR:
            tap(k, mode == NUMBER ? NUM_STAR : SYMBOLS);
            return true;
        default:
            if (Keymap.isDigit(k)) {
                if (mode == NUMBER || caseMode == CASE_NUM) {
                    commitPending();
                    insert((char) k);
                    repaint();
                } else if (k == '0') {
                    commitPending();
                    insert(' ');
                    repaint();
                } else {
                    tap(k, LETTERS[k - '0']);
                }
                return true;
            }
            return false;
        }
    }

    void keyLong(int k) {
        if (k == Keymap.SOFT_R || k == Keymap.CLEAR) {
            commitPending();
            buf.setLength(0);
            cursor = 0;
            repaint();
        } else if (Keymap.isDigit(k) && mode == TEXT && caseMode != CASE_NUM) {
            // long press: the digit itself replaces the letter being tapped
            if (pendingKey == k && cursor > 0) {
                buf.setCharAt(cursor - 1, (char) k);
            } else {
                insert((char) k);
            }
            commitPending();
            repaint();
        }
    }

    void keyRepeat(int k) {
        if (k == Keymap.LEFT || k == Keymap.RIGHT || k == Keymap.UP || k == Keymap.DOWN) {
            key(k);
        }
    }

    /* ---------------- painting ---------------- */

    private int charsPerLine() {
        return Math.max(1, (Theme.W - 2 * Theme.MARGIN - 8) / Theme.CH_W);
    }

    void paint(Graphics g, int x, int y, int w, int h) {
        int cpl = charsPerLine();
        int lineH = Theme.FONT_H + 2;
        int rows = Math.max(1, (h - 8) / lineH);
        int n = buf.length();
        int totalLines = n / cpl + 1;
        int curLine = cursor / cpl;
        if (curLine < firstLine) {
            firstLine = curLine;
        } else if (curLine >= firstLine + rows) {
            firstLine = curLine - rows + 1;
        }
        int tx = x + Theme.MARGIN + 2;
        int ty = y + 4;
        for (int l = 0; l < rows; l++) {
            int line = firstLine + l;
            int s = line * cpl;
            if (s > n) {
                break;
            }
            int e = Math.min(n, s + cpl);
            int ly = ty + l * lineH;
            for (int i = s; i < e; i++) {
                boolean pend = (pendingKey != Keymap.NONE && i == cursor - 1);
                if (pend) {
                    g.setColor(Theme.C_SEL);
                    g.fillRect(tx + (i - s) * Theme.CH_W, ly, Theme.CH_W, Theme.FONT_H);
                    g.setColor(Theme.C_SEL_TEXT);
                } else {
                    g.setColor(Theme.C_TEXT);
                }
                g.drawChar(buf.charAt(i), tx + (i - s) * Theme.CH_W, ly,
                           Graphics.TOP | Graphics.LEFT);
            }
            if (line == curLine && cursorOn) {
                int cx = tx + (cursor - s) * Theme.CH_W;
                g.setColor(Theme.C_TEXT);
                g.fillRect(cx, ly, 1, Theme.FONT_H);
            }
        }
        Theme.scrollBar(g, x + w - 6, y, h, totalLines, rows, firstLine);
        // length counter bottom right
        g.setColor(Theme.C_TEXT_DIM);
        g.drawString(n + "/" + maxLen, x + w - Theme.MARGIN - 8, y + h - Theme.FONT_H - 2,
                     Graphics.TOP | Graphics.RIGHT);
    }
}
