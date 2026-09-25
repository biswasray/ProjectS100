/*
 * S100 shell - the idle screen.
 *
 * Wallpaper, big clock, date, operator/profile line and the classic
 * Series 40 soft keys "Go to" / "Menu" / "Names". Digits open the dialer,
 * the Call key the dialled numbers, navigation keys run the shortcuts
 * from Settings > My shortcuts, long '#' toggles Silent, long End asks
 * to switch the phone off. "Menu, *" locks the keypad, "Unlock, *"
 * releases it.
 */

package com.sun.midp.appmanager;

import java.util.Calendar;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

class HomeScreen extends Screen {
    private static final int CLOCK_SCALE = 5;

    private Image wallpaper;
    private String wallpaperKind;
    private boolean locked;
    private long unlockArmedAt;

    HomeScreen() {
        super(null);
    }

    boolean overlayStatus() { return true; }

    String softLeft() { return locked ? "Unlock" : "Go to"; }
    String softMid() { return locked ? null : "Menu"; }
    String softRight() { return locked ? null : "Names"; }

    void lock() {
        locked = true;
        unlockArmedAt = 0;
        repaint();
    }

    void unlock() {
        locked = false;
        unlockArmedAt = 0;
        repaint();
    }

    boolean isLocked() {
        return locked;
    }

    void tick() {
        repaint();
    }

    /* ---------------- painting ---------------- */

    Image wallpaperImage(int w, int h) {
        String kind = Prefs.get(Prefs.WALLPAPER, "blue");
        if (wallpaper != null && kind.equals(wallpaperKind)) {
            return wallpaper;
        }
        wallpaperKind = kind;
        wallpaper = Image.createImage(w, h);
        Graphics g = wallpaper.getGraphics();
        if (kind.equals("plain")) {
            g.setColor(Theme.C_WALL_MID);
            g.fillRect(0, 0, w, h);
        } else if (kind.equals("dark")) {
            Theme.gradient(g, 0, 0, w, h, 0x101418, 0x2C3A4C);
        } else {
            Theme.gradient(g, 0, 0, w, h / 2, Theme.C_WALL_TOP, Theme.C_WALL_MID);
            Theme.gradient(g, 0, h / 2, w, h - h / 2, Theme.C_WALL_MID, Theme.C_WALL_BOT);
            // soft "waves" like the Series 40 default theme
            g.setColor(0x3F7FE0);
            g.fillArc(-60, h - 150, w + 160, 220, 0, 180);
            g.setColor(0x5B96EA);
            g.fillArc(-120, h - 110, w + 200, 200, 0, 180);
            g.setColor(0x7FB0F2);
            g.fillArc(-40, h - 70, w + 120, 160, 0, 180);
        }
        return wallpaper;
    }

    void paint(Graphics g, int x, int y, int w, int h) {
        g.drawImage(wallpaperImage(w, h), x, y, Graphics.TOP | Graphics.LEFT);

        Calendar now = Clock.now();
        String time = Clock.time(now);
        int tw = time.length() * Theme.bigWidth(CLOCK_SCALE) - CLOCK_SCALE;
        int tx = x + (w - tw) / 2;
        int ty = y + 62;
        g.setColor(Theme.C_HOME_SHADOW);
        Theme.bigString(g, time, tx + 2, ty + 2, CLOCK_SCALE);
        g.setColor(Theme.C_HOME_TEXT);
        Theme.bigString(g, time, tx, ty, CLOCK_SCALE);
        String ampm = Clock.ampm(now);
        if (ampm.length() > 0) {
            Theme.shadowText(g, ampm, tx + tw + 6, ty + Theme.bigHeight(CLOCK_SCALE) - Theme.FONT_H,
                             Graphics.TOP | Graphics.LEFT, Theme.C_HOME_TEXT, Theme.C_HOME_SHADOW);
        }
        int dy = ty + Theme.bigHeight(CLOCK_SCALE) + 10;
        Theme.shadowText(g, Clock.longDate(now), x + w / 2, dy, Graphics.TOP | Graphics.HCENTER,
                         Theme.C_HOME_TEXT, Theme.C_HOME_SHADOW);

        String operator = Prefs.get(Prefs.OPERATOR, "JioPhone");
        String profile = Prefs.get(Prefs.PROFILE, "General");
        int oy = y + h - 60;
        Theme.shadowText(g, operator, x + w / 2, oy, Graphics.TOP | Graphics.HCENTER,
                         Theme.C_HOME_TEXT, Theme.C_HOME_SHADOW);
        if (!profile.equals("General")) {
            Theme.shadowText(g, profile, x + w / 2, oy + Theme.FONT_H + 2,
                             Graphics.TOP | Graphics.HCENTER, Theme.C_HOME_TEXT,
                             Theme.C_HOME_SHADOW);
        }
        if (locked) {
            int bw = 150, bh = 36;
            int bx = x + (w - bw) / 2, by = y + h - 24 - bh;
            g.setColor(Theme.C_HOME_SHADOW);
            g.fillRoundRect(bx, by, bw, bh, 8, 8);
            g.setColor(Theme.C_HOME_TEXT);
            g.drawRoundRect(bx, by, bw - 1, bh - 1, 8, 8);
            g.drawString("Keypad locked", x + w / 2, by + 4, Graphics.TOP | Graphics.HCENTER);
            g.drawString("Unlock, then *", x + w / 2, by + 4 + Theme.FONT_H,
                         Graphics.TOP | Graphics.HCENTER);
        }
    }

    /* ---------------- keys ---------------- */

    boolean key(int k) {
        MenuManager m = shell.menus;
        if (locked) {
            long now = System.currentTimeMillis();
            if (k == Keymap.SOFT_L) {
                unlockArmedAt = now;
            } else if (k == Keymap.STAR && now - unlockArmedAt < 2500) {
                unlockArmedAt = 0;
                shell.menus.security.unlockKeypad();   // may ask for the code
            }
            repaint();                // the banner already says "Unlock, then *"
            return true;
        }
        switch (k) {
        case Keymap.SELECT:
            m.openMain();
            return true;
        case Keymap.SOFT_L:
            goTo();
            return true;
        case Keymap.SOFT_R:
            m.openId("contacts.names");
            return true;
        case Keymap.SEND:
            m.openId("log.dialled");
            return true;
        case Keymap.UP:
        case Keymap.DOWN:
        case Keymap.LEFT:
        case Keymap.RIGHT: {
            String id = Keymap.shortcut(k);
            if (id != null && !m.openId(id)) {
                shell.info("Shortcut not set");
            }
            return true;
        }
        case Keymap.END:
        case Keymap.CLEAR:
        case Keymap.POUND:
            return true;              // long press variants only
        default:
            if (Keymap.isDialKey(k)) {
                shell.push(new DialerScreen(String.valueOf((char) k)));
                return true;
            }
            return false;
        }
    }

    void keyLong(int k) {
        if (locked) {
            return;
        }
        if (k == Keymap.POUND) {
            String p = Prefs.get(Prefs.PROFILE, "General");
            String next = p.equals("Silent") ? "General" : "Silent";
            Prefs.set(Prefs.PROFILE, next);
            shell.info(next.equals("Silent") ? "Silent" : "General");
        } else if (k == Keymap.END) {
            shell.showPopup(Popup.confirm("Switch off?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        Sys.power("off");
                        shell.ams.shutdown();
                    }
                }
            }));
        }
    }

    /** The "Go to" personal shortcut list. */
    private static final String[] GOTO_IDS = {
        "messaging.compose", "messaging.inbox", "contacts.names", "contacts.add",
        "log.dialled", "settings.profiles", "organiser.calculator",
        "organiser.stopwatch", "organiser.notes", "applications.collection"
    };

    private void goTo() {
        final MenuManager m = shell.menus;
        String[] labels = new String[GOTO_IDS.length];
        for (int i = 0; i < labels.length; i++) {
            MenuItem it = m.find(GOTO_IDS[i]);
            labels[i] = (it == null) ? GOTO_IDS[i] : it.label;
        }
        shell.showPopup(Popup.menu("Go to", labels, new Popup.Listener() {
            public void onResult(int r) {
                m.openId(GOTO_IDS[r]);
            }
        }));
    }
}
