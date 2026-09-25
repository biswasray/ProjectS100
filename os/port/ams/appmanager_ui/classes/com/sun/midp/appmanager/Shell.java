/*
 * S100 shell - the full-screen Canvas every shell screen is drawn on.
 *
 * Owns the screen stack (HomeScreen at the bottom), the modal popup, the
 * status bar and soft key bar, long-press detection and the minute
 * timer. Everything that touches UI state runs on the LCDUI event thread:
 * timer callbacks are re-posted with Display.callSerially().
 */

package com.sun.midp.appmanager;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;

class Shell extends Canvas {
    final Display display;
    final AppManagerUIImpl ams;
    final MenuManager menus;

    private final Vector stack = new Vector();
    private Popup popup;

    private Timer timer;
    private TimerTask longPressTask;
    private TimerTask minuteTask;
    /** Automatic keyguard (Settings > Security): last key press, checker. */
    private long lastKeyAt = System.currentTimeMillis();
    private TimerTask idleTask;
    /** Screen timeout (Settings > Display): backlight is off, level to restore. */
    private boolean screenOff;
    private int onLevel = 128;
    /** The key that woke the screen: its repeats and long press are swallowed. */
    private boolean wakeKey;
    private int heldKey = Keymap.NONE;
    private boolean longFired;
    /** Action waiting for the current key to be released (see whenReleased). */
    private Runnable afterRelease;

    Shell(Display display, AppManagerUIImpl ams) {
        this.display = display;
        this.ams = ams;
        setFullScreenMode(true);
        menus = new MenuManager(this);
        push(new HomeScreen());
        menus.security.startup();          // phone lock: asks the code first
    }

    /* ---------------- screen stack ---------------- */

    Screen top() {
        return (Screen) stack.elementAt(stack.size() - 1);
    }

    HomeScreen home() {
        return (HomeScreen) stack.elementAt(0);
    }

    boolean atHome() {
        return stack.size() == 1;
    }

    void push(Screen s) {
        if (!stack.isEmpty()) {
            top().onHide();
        }
        s.shell = this;
        stack.addElement(s);
        s.onShow();
        repaint();
    }

    void pop() {
        if (stack.size() <= 1) {
            return;
        }
        Screen s = top();
        stack.removeElementAt(stack.size() - 1);
        s.onHide();
        top().onShow();
        repaint();
    }

    /** Pops everything above s (s stays). */
    void popTo(Screen s) {
        while (stack.size() > 1 && top() != s) {
            pop();
        }
    }

    /** Replaces the top screen (used by wizards: input -> result). */
    void replace(Screen s) {
        if (stack.size() > 1) {
            Screen old = top();
            stack.removeElementAt(stack.size() - 1);
            old.onHide();
        }
        push(s);
    }

    /** Back to the idle screen, closing any popup. */
    void goHome() {
        popup = null;
        while (stack.size() > 1) {
            pop();
        }
        repaint();
    }

    /* ---------------- popups ---------------- */

    void showPopup(Popup p) {
        p.shell = this;
        popup = p;
        repaint();
    }

    void closePopup() {
        popup = null;
        repaint();
    }

    Popup popup() {
        return popup;
    }

    void info(String text) {
        showPopup(Popup.info(text, 1500));
    }

    /* ---------------- painting ---------------- */

    public void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        Screen s = top();
        int contentTop;
        g.setClip(0, 0, w, h);
        if (s.overlayStatus()) {
            g.setClip(0, 0, w, h - Theme.SOFT_H);
            s.paint(g, 0, 0, w, h - Theme.SOFT_H);
            g.setClip(0, 0, w, h);
            StatusBar.paint(g, w, true);
        } else {
            StatusBar.paint(g, w, false);
            contentTop = Theme.STATUS_H;
            if (s.title != null) {
                Theme.titleBar(g, contentTop, s.title, s.titleRight());
                contentTop += Theme.TITLE_H;
            }
            int ch = h - Theme.SOFT_H - contentTop;
            g.setColor(Theme.C_BG);
            g.fillRect(0, contentTop, w, ch);
            g.setClip(0, contentTop, w, ch);
            s.paint(g, 0, contentTop, w, ch);
            g.setClip(0, 0, w, h);
        }
        String l, m, r;
        if (popup != null) {
            popup.paint(g, w, h - Theme.SOFT_H);
            l = popup.softLeft();
            m = popup.softMid();
            r = popup.softRight();
        } else {
            l = s.softLeft();
            m = s.softMid();
            r = s.softRight();
        }
        paintSoftBar(g, w, h, l, m, r);
    }

    private void paintSoftBar(Graphics g, int w, int h, String l, String m,
                              String r) {
        int y = h - Theme.SOFT_H;
        g.setColor(Theme.C_SOFT_BG);
        g.fillRect(0, y, w, Theme.SOFT_H);
        g.setColor(Theme.C_SOFT_LINE);
        g.drawLine(0, y, w, y);
        g.setColor(Theme.C_SOFT_TEXT);
        int ty = y + (Theme.SOFT_H - Theme.FONT_H) / 2 + 1;
        if (l != null) {
            Theme.bold(g, l, Theme.MARGIN, ty, Graphics.TOP | Graphics.LEFT);
        }
        if (m != null) {
            Theme.bold(g, m, w / 2, ty, Graphics.TOP | Graphics.HCENTER);
        }
        if (r != null) {
            Theme.bold(g, r, w - Theme.MARGIN, ty, Graphics.TOP | Graphics.RIGHT);
        }
    }

    /* ---------------- keys ---------------- */

    protected void keyPressed(int code) {
        lastKeyAt = System.currentTimeMillis();
        if (screenOff) {
            // the first key only turns the screen back on
            wake();
            wakeKey = true;
            return;
        }
        int k = Keymap.map(code);
        if (k == Keymap.NONE) {
            return;
        }
        armLongPress(k);
        if (popup != null) {
            popup.key(k);
            return;
        }
        Screen s = top();
        if (s.key(k)) {
            return;
        }
        // shell defaults
        if (k == Keymap.SOFT_R) {
            s.back();
        } else if (k == Keymap.END) {
            if (!atHome()) {
                goHome();
            }
        }
    }

    protected void keyRepeated(int code) {
        int k = Keymap.map(code);
        if (k == Keymap.NONE || wakeKey) {
            return;
        }
        if (popup != null) {
            popup.keyRepeat(k);
        } else {
            top().keyRepeat(k);
        }
    }

    protected void keyReleased(int code) {
        wakeKey = false;
        cancelLongPress();
        Runnable r = afterRelease;
        afterRelease = null;
        if (r != null) {
            r.run();
        }
    }

    /**
     * Runs r once the key that is currently held has been released (at
     * once if none is). The shell acts on key presses; anything that
     * brings another MIDlet to the foreground must wait for the release,
     * or that release lands in the new MIDlet where Chameleon fires soft
     * key commands on release - the installer, for instance, saw the
     * release of our "Install" as its "Stop".
     */
    void whenReleased(final Runnable r) {
        if (heldKey == Keymap.NONE) {
            r.run();
            return;
        }
        afterRelease = r;
        // the release can get lost (focus change): run it anyway shortly
        later(new Runnable() {
            public void run() {
                if (afterRelease == r) {
                    afterRelease = null;
                    r.run();
                }
            }
        }, 700);
    }

    private void armLongPress(final int k) {
        cancelLongPress();
        heldKey = k;
        longFired = false;
        longPressTask = new TimerTask() {
            public void run() {
                display.callSerially(new Runnable() {
                    public void run() {
                        if (heldKey == k && !longFired) {
                            longFired = true;
                            if (popup != null) {
                                popup.keyLong(k);
                            } else {
                                top().keyLong(k);
                            }
                        }
                    }
                });
            }
        };
        timer().schedule(longPressTask, Keymap.LONG_PRESS_MS);
    }

    private void cancelLongPress() {
        heldKey = Keymap.NONE;
        if (longPressTask != null) {
            longPressTask.cancel();
            longPressTask = null;
        }
    }

    /* ---------------- timers ---------------- */

    private synchronized Timer timer() {
        if (timer == null) {
            timer = new Timer();
        }
        return timer;
    }

    /** Runs r on the event thread after `ms` milliseconds. */
    void later(final Runnable r, long ms) {
        timer().schedule(new TimerTask() {
            public void run() {
                display.callSerially(r);
            }
        }, ms);
    }

    /** Repeating timer; returns the task so the caller can cancel it. */
    TimerTask every(final Runnable r, long periodMs) {
        TimerTask t = new TimerTask() {
            public void run() {
                display.callSerially(r);
            }
        };
        timer().schedule(t, periodMs, periodMs);
        return t;
    }

    protected void showNotify() {
        StatusBar.refresh();
        long now = System.currentTimeMillis();
        long toMinute = 60000 - (now % 60000) + 200;
        if (minuteTask != null) {
            minuteTask.cancel();
        }
        minuteTask = new TimerTask() {
            public void run() {
                display.callSerially(new Runnable() {
                    public void run() {
                        StatusBar.refresh();
                        top().tick();
                        repaint();
                    }
                });
            }
        };
        timer().schedule(minuteTask, toMinute, 60000);
        if (idleTask != null) {
            idleTask.cancel();
        }
        lastKeyAt = System.currentTimeMillis();
        idleTask = every(new Runnable() {
            public void run() { checkIdle(); }
        }, 5000);
        top().onShow();
        repaint();
    }

    /**
     * Screen timeout (backlight off) and automatic keyguard (locks the idle
     * screen) after their configured idle times.
     */
    private void checkIdle() {
        long idle = System.currentTimeMillis() - lastKeyAt;
        int off = SettingsMenu.screenTimeoutSeconds();
        if (top().keepScreenOn()) {
            lastKeyAt = System.currentTimeMillis();
        } else if (off > 0 && !screenOff && idle >= off * 1000L) {
            sleepScreen();
        }
        int secs = SecuritySettings.autoLockSeconds();
        if (secs <= 0 || !atHome() || popup != null || home().isLocked()) {
            return;
        }
        if (idle >= secs * 1000L) {
            home().lock();
            repaint();
        }
    }

    private void sleepScreen() {
        int level = Sys.backlight();
        if (level > 0) {
            onLevel = level;               // keep whatever j2me.sh set
        }
        Sys.backlight(0);
        screenOff = true;
    }

    /** Turns the backlight back on after the screen timeout; restarts the idle count. */
    void wake() {
        lastKeyAt = System.currentTimeMillis();
        if (screenOff) {
            screenOff = false;
            Sys.backlight(onLevel);
            repaint();
        }
    }

    protected void hideNotify() {
        wake();                            // a MIDlet takes the screen
        if (minuteTask != null) {
            minuteTask.cancel();
            minuteTask = null;
        }
        if (idleTask != null) {
            idleTask.cancel();
            idleTask = null;
        }
        cancelLongPress();
    }

    /** Makes this canvas current again (after an LCDUI Alert/Form). */
    void show() {
        if (display.getCurrent() != this) {
            display.setCurrent(this);
        }
        repaint();
    }
}
