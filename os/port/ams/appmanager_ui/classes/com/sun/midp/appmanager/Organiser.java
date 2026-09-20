/*
 * S100 shell - Organiser: Calculator, Stopwatch, Notes.
 */

package com.sun.midp.appmanager;

import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

class Organiser {
    private final Shell shell;

    Organiser(Shell shell) {
        this.shell = shell;
    }

    MenuItem[] items() {
        return new MenuItem[] {
            new MenuItem("organiser.calculator", "Calculator", "calculator", new Runnable() {
                public void run() { shell.push(new CalculatorScreen()); }
            }),
            new MenuItem("organiser.stopwatch", "Stopwatch", "stopwatch", new Runnable() {
                public void run() { shell.push(new StopwatchScreen()); }
            }),
            new MenuItem("organiser.notes", "Notes", "notes", new Runnable() {
                public void run() { shell.push(new NotesScreen()); }
            }),
        };
    }

    /* ================= Calculator ================= */

    /**
     * Series 40 calculator: type a number, '*' cycles + - x /, '#' is the
     * decimal point, centre key or Options > Equals evaluates.
     */
    static class CalculatorScreen extends Screen {
        private static final char[] OPS = {'+', '-', '*', '/'};
        private final StringBuffer entry = new StringBuffer();
        private double acc;
        private char op;            // pending operator or 0
        private boolean fresh;      // entry shows a result; next digit starts anew
        private String error;

        CalculatorScreen() {
            super("Calculator");
        }

        String softLeft() { return "Options"; }
        String softMid() { return "="; }

        private String shown() {
            if (error != null) {
                return error;
            }
            return entry.length() == 0 ? "0" : entry.toString();
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            int by = y + 10;
            g.setColor(0xE8F0E0);
            g.fillRoundRect(x + 8, by, w - 16, 60, 6, 6);
            g.setColor(Theme.C_FRAME);
            g.drawRoundRect(x + 8, by, w - 17, 59, 6, 6);
            g.setColor(Theme.C_TEXT_DIM);
            String pend = (op == 0) ? "" : fmt(acc) + " " + (op == '*' ? 'x' : op);
            g.drawString(pend, x + w - 16, by + 6, Graphics.TOP | Graphics.RIGHT);
            g.setColor(Theme.C_TEXT);
            Theme.bold(g, Theme.fit(shown(), w - 32), x + w - 16, by + 60 - Theme.FONT_H - 8,
                       Graphics.TOP | Graphics.RIGHT);
            g.setColor(Theme.C_TEXT_DIM);
            int ly = by + 80;
            String[] help = {"* : + - x /", "# : decimal point", "Clear : delete",
                             "Centre : equals"};
            for (int i = 0; i < help.length; i++) {
                g.drawString(help[i], x + 16, ly + i * (Theme.FONT_H + 2),
                             Graphics.TOP | Graphics.LEFT);
            }
        }

        static String fmt(double v) {
            if (v == (long) v && Math.abs(v) < 1e15) {
                return Long.toString((long) v);
            }
            String s = Double.toString(v);
            // trim float noise like 0.30000000000000004
            int dot = s.indexOf('.');
            if (dot >= 0 && s.indexOf('E') < 0 && s.length() - dot > 10) {
                s = s.substring(0, dot + 10);
            }
            while (s.indexOf('.') >= 0 && (s.endsWith("0") || s.endsWith("."))) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        }

        private double value() {
            if (entry.length() == 0) {
                return fresh ? acc : 0;
            }
            try {
                return Double.parseDouble(entry.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private void apply(char nextOp) {
            error = null;
            double v = value();
            if (op != 0 && !fresh) {
                switch (op) {
                case '+': acc += v; break;
                case '-': acc -= v; break;
                case '*': acc *= v; break;
                case '/':
                    if (v == 0) {
                        error = "Cannot divide by zero";
                        op = 0;
                        entry.setLength(0);
                        repaint();
                        return;
                    }
                    acc /= v;
                    break;
                }
            } else if (!fresh) {
                acc = v;
            }
            op = nextOp;
            entry.setLength(0);
            if (nextOp == 0) {
                entry.append(fmt(acc));
                fresh = true;
            } else {
                fresh = false;
            }
            repaint();
        }

        private void typed(char c) {
            error = null;
            if (fresh) {
                entry.setLength(0);
                fresh = false;
                if (op == 0) {
                    acc = 0;
                }
            }
            if (c == '.' && entry.toString().indexOf('.') >= 0) {
                return;
            }
            if (entry.length() < 16) {
                entry.append(c);
            }
            repaint();
        }

        boolean key(int k) {
            if (Keymap.isDigit(k)) {
                typed((char) k);
                return true;
            }
            switch (k) {
            case Keymap.POUND:
                typed('.');
                return true;
            case Keymap.STAR: {
                int i = 0;
                if (op != 0 && entry.length() == 0) {
                    while (i < OPS.length && OPS[i] != op) {
                        i++;
                    }
                    i = (i + 1) % OPS.length;
                    op = OPS[i];
                    repaint();
                } else {
                    apply('+');
                }
                return true;
            }
            case Keymap.SELECT:
                apply((char) 0);
                return true;
            case Keymap.CLEAR:
                error = null;
                if (entry.length() > 0 && !fresh) {
                    entry.setLength(entry.length() - 1);
                } else {
                    entry.setLength(0);
                    op = 0;
                    acc = 0;
                    fresh = false;
                }
                repaint();
                return true;
            case Keymap.SOFT_L: {
                String[] opts = {"Equals", "Add", "Subtract", "Multiply", "Divide", "Clear all"};
                shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
                    public void onResult(int r) {
                        switch (r) {
                        case 0: apply((char) 0); break;
                        case 1: apply('+'); break;
                        case 2: apply('-'); break;
                        case 3: apply('*'); break;
                        case 4: apply('/'); break;
                        default:
                            entry.setLength(0);
                            op = 0;
                            acc = 0;
                            fresh = false;
                            error = null;
                            repaint();
                        }
                    }
                }));
                return true;
            }
            case Keymap.SOFT_R:
                if (entry.length() > 0 && !fresh) {
                    entry.setLength(entry.length() - 1);
                    repaint();
                    return true;
                }
                return false;
            default:
                return false;
            }
        }

        String softRight() {
            return (entry.length() > 0 && !fresh) ? "Clear" : "Back";
        }
    }

    /* ================= Stopwatch ================= */

    static class StopwatchScreen extends Screen {
        private long startedAt;
        private long elapsed;
        private boolean running;
        private TimerTask ticker;
        private final Vector laps = new Vector();

        StopwatchScreen() {
            super("Stopwatch");
        }

        private long now() {
            return running ? elapsed + System.currentTimeMillis() - startedAt : elapsed;
        }

        String softLeft() { return running ? "Stop" : (elapsed > 0 ? "Continue" : "Start"); }
        String softMid() { return running ? "Lap" : null; }
        String softRight() { return (!running && elapsed > 0) ? "Reset" : "Back"; }

        void onHide() {
            stopTicker();
        }

        void onShow() {
            if (running) {
                startTicker();
            }
        }

        private void startTicker() {
            if (ticker == null) {
                ticker = shell.every(new Runnable() {
                    public void run() { repaint(); }
                }, 100);
            }
        }

        private void stopTicker() {
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
        }

        static String format(long ms) {
            long t = ms / 100;
            long tenths = t % 10;
            long s = (t / 10) % 60;
            long m = (t / 600) % 100;
            return Theme.two((int) m) + ":" + Theme.two((int) s) + "." + tenths;
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            String t = format(now());
            String big = t.substring(0, 5);
            int scale = 5;
            int bw = 5 * Theme.bigWidth(scale) - scale;
            int bx = x + (w - bw - 20) / 2;
            int by = y + 24;
            g.setColor(Theme.C_TITLE_TOP);
            Theme.bigString(g, big, bx, by, scale);
            Theme.bold(g, t.substring(5), bx + bw + 4, by + Theme.bigHeight(scale) - Theme.FONT_H,
                       Graphics.TOP | Graphics.LEFT);
            g.setColor(Theme.C_TEXT);
            int ly = by + Theme.bigHeight(scale) + 16;
            int n = laps.size();
            int show = Math.min(n, (h - (ly - y)) / (Theme.FONT_H + 2));
            for (int i = 0; i < show; i++) {
                int idx = n - 1 - i;
                g.drawString("Lap " + (idx + 1) + "   " + format(((Long) laps.elementAt(idx)).longValue()),
                             x + w / 2, ly + i * (Theme.FONT_H + 2), Graphics.TOP | Graphics.HCENTER);
            }
        }

        boolean key(int k) {
            switch (k) {
            case Keymap.SOFT_L:
                if (running) {
                    elapsed = now();
                    running = false;
                    stopTicker();
                } else {
                    startedAt = System.currentTimeMillis();
                    running = true;
                    startTicker();
                }
                repaint();
                return true;
            case Keymap.SELECT:
                if (running) {
                    laps.addElement(new Long(now()));
                    repaint();
                } else {
                    key(Keymap.SOFT_L);
                }
                return true;
            case Keymap.SOFT_R:
            case Keymap.CLEAR:
                if (!running && elapsed > 0) {
                    elapsed = 0;
                    laps.removeAllElements();
                    repaint();
                    return true;
                }
                return false;
            default:
                return false;
            }
        }
    }

    /* ================= Notes ================= */

    static final String NOTES_STORE = "s100_notes";

    static class Note {
        int id;
        String text;
    }

    static Vector notes() {
        Vector v = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(NOTES_STORE, true);
            RecordEnumeration e = rs.enumerateRecords(null, null, false);
            while (e.hasNextElement()) {
                int id = e.nextRecordId();
                Note n = new Note();
                n.id = id;
                byte[] d = rs.getRecord(id);
                n.text = (d == null) ? "" : new String(d);
                v.addElement(n);
            }
            e.destroy();
        } catch (Exception ex) {
            // empty
        } finally {
            Prefs.close(rs);
        }
        return v;
    }

    static void saveNote(Note n) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(NOTES_STORE, true);
            byte[] d = n.text.getBytes();
            if (n.id == 0) {
                n.id = rs.addRecord(d, 0, d.length);
            } else {
                rs.setRecord(n.id, d, 0, d.length);
            }
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
        }
    }

    static void deleteNote(Note n) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(NOTES_STORE, true);
            rs.deleteRecord(n.id);
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
        }
    }

    class NotesScreen extends ListScreen {
        NotesScreen() {
            super("Notes");
            emptyText = "No notes";
        }

        void refresh() {
            items.removeAllElements();
            add(new Item("Add note", null));
            Vector v = notes();
            for (int i = 0; i < v.size(); i++) {
                Note n = (Note) v.elementAt(i);
                add(new Item(n.text.replace('\n', ' '), n));
            }
        }

        String[] optionsMenu(Item it) {
            return it.data == null ? null : new String[] {"Open", "Edit", "Delete"};
        }

        private void edit(final Note n) {
            shell.push(new TextInputScreen("Note:", n == null ? null : n.text,
                TextInputScreen.TEXT, 500, "Save", new TextInputScreen.Listener() {
                    public void onText(String t) {
                        if (t.trim().length() == 0) {
                            return;
                        }
                        Note nn = (n == null) ? new Note() : n;
                        nn.text = t;
                        saveNote(nn);
                        shell.info("Saved");
                    }
                }));
        }

        void select(Item it) {
            if (it.data == null) {
                edit(null);
            } else {
                shell.push(new TextViewScreen("Note", ((Note) it.data).text));
            }
        }

        void option(Item it, int r) {
            final Note n = (Note) it.data;
            if (r == 0) {
                select(it);
            } else if (r == 1) {
                edit(n);
            } else {
                clear(it);
            }
        }

        void clear(Item it) {
            if (it.data == null) {
                return;
            }
            final Note n = (Note) it.data;
            shell.showPopup(Popup.confirm("Delete note?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        deleteNote(n);
                        refresh();
                        clamp();
                        repaint();
                    }
                }
            }));
        }
    }
}
