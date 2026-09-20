/*
 * S100 shell - Settings > Security (the Series 40 set):
 *
 *   Phone lock            ask the security code when Java mode starts
 *   Security keyguard     ask it to unlock the keypad, too
 *   Automatic keyguard    lock the keypad after some idle time on the
 *                         idle screen (Shell keeps the idle timer)
 *   Change security code  default 12345, stored as a salted hash in Prefs
 *   PIN code request      the SIM PIN belongs to KaiOS; explained
 *   Application permissions  the AMS's per-suite settings (Collection)
 *   Certificates          the CA manager, when built in
 *   Lock keypad now
 *
 * CodeScreen is the masked number entry used for all of it.
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Graphics;

class SecuritySettings {
    static final String P_CODE = "sec.code";            // hash of the security code
    static final String P_PHONE_LOCK = "sec.phonelock"; // 1 | 0
    static final String P_KEYGUARD = "sec.keyguard";    // 1 = code needed to unlock keypad
    static final String P_AUTOLOCK = "sec.autolock";    // seconds, 0 = off
    static final String DEFAULT_CODE = "12345";

    static final String[] AUTOLOCK_LABELS = {"Off", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes"};
    static final String[] AUTOLOCK_KEYS = {"0", "30", "60", "120", "300", "600"};

    private final Shell shell;

    SecuritySettings(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("settings.security", "Security", "security", new Runnable() {
            public void run() { shell.push(new SecurityScreen()); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*                              the code                              */
    /* ------------------------------------------------------------------ */

    /** FNV-1a over a salted code; enough for a 4-8 digit lock code. */
    static String hash(String code) {
        String s = "s100:" + code;
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return Integer.toHexString(h);
    }

    static boolean check(String code) {
        return hash(code).equals(Prefs.get(P_CODE, hash(DEFAULT_CODE)));
    }

    static boolean phoneLockOn() {
        return Prefs.getBool(P_PHONE_LOCK, false);
    }

    static boolean keyguardCode() {
        return Prefs.getBool(P_KEYGUARD, false);
    }

    /** Idle seconds before the keypad locks by itself, 0 = never. */
    static int autoLockSeconds() {
        return Prefs.getInt(P_AUTOLOCK, 0);
    }

    /** Called by the shell once the idle screen exists. */
    void startup() {
        if (phoneLockOn()) {
            shell.home().lock();
            shell.push(new CodeScreen("Phone lock", "Security code:", false, new CodeListener() {
                public boolean onCode(String code) {
                    if (!check(code)) {
                        return false;
                    }
                    shell.home().unlock();
                    return true;
                }
            }));
        }
    }

    /** "Unlock, then *" on the idle screen. */
    void unlockKeypad() {
        if (!keyguardCode()) {
            shell.home().unlock();
            shell.info("Keypad unlocked");
            return;
        }
        shell.push(new CodeScreen("Security keyguard", "Security code:", true, new CodeListener() {
            public boolean onCode(String code) {
                if (!check(code)) {
                    return false;
                }
                shell.home().unlock();
                return true;
            }
        }));
    }

    /** Runs r after the security code was typed correctly. */
    void verify(String title, final Runnable r) {
        shell.push(new CodeScreen(title, "Security code:", true, new CodeListener() {
            public boolean onCode(String code) {
                if (!check(code)) {
                    return false;
                }
                shell.later(r, 50);           // after this screen is gone
                return true;
            }
        }));
    }

    /* ------------------------------------------------------------------ */
    /*                            the settings                            */
    /* ------------------------------------------------------------------ */

    class SecurityScreen extends ListScreen {
        SecurityScreen() {
            super("Security");
        }

        void refresh() {
            items.removeAllElements();
            add(new Item("Phone lock", "lock")).value(phoneLockOn() ? "On" : "Off");
            add(new Item("Security keyguard", "guard")).value(keyguardCode() ? "On" : "Off");
            add(new Item("Automatic keyguard", "auto")).value(
                AUTOLOCK_LABELS[SettingsMenu.indexOf(AUTOLOCK_KEYS, String.valueOf(autoLockSeconds()))]);
            add(new Item("Change security code", "code"));
            add(new Item("PIN code request", "pin"));
            add(new Item("Application permissions", "perms"));
            add(new Item("Certificates", "certs"));
            add(new Item("Lock keypad now", "locknow"));
        }

        String softLeft() { return current() == null ? null : "Select"; }

        private void toggle(final String title, final String pref) {
            verify(title, new Runnable() {
                public void run() {
                    shell.showPopup(Popup.choice(title, new String[] {"Off", "On"},
                        Prefs.getBool(pref, false) ? 1 : 0, new Popup.Listener() {
                            public void onResult(int r) {
                                Prefs.setBool(pref, r == 1);
                                refresh();
                                repaint();
                            }
                        }));
                }
            });
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("lock")) {
                toggle("Phone lock", P_PHONE_LOCK);
            } else if (what.equals("guard")) {
                toggle("Security keyguard", P_KEYGUARD);
            } else if (what.equals("auto")) {
                shell.showPopup(Popup.choice("Automatic keyguard", AUTOLOCK_LABELS,
                    SettingsMenu.indexOf(AUTOLOCK_KEYS, String.valueOf(autoLockSeconds())),
                    new Popup.Listener() {
                        public void onResult(int r) {
                            Prefs.set(P_AUTOLOCK, AUTOLOCK_KEYS[r]);
                            refresh();
                            repaint();
                        }
                    }));
            } else if (what.equals("code")) {
                changeCode();
            } else if (what.equals("pin")) {
                shell.showPopup(Popup.info("The SIM card's PIN request is handled by KaiOS "
                    + "when the phone boots (Settings > Privacy & Security > SIM security). "
                    + "Java mode cannot talk to the SIM.", 0));
            } else if (what.equals("perms")) {
                shell.push(shell.menus.apps.new CollectionScreen());
                shell.info("Options > Application settings");
            } else if (what.equals("certs")) {
                if (shell.ams.hasCaManager()) {
                    shell.ams.launchCaManager();
                } else {
                    shell.showPopup(Popup.info("The certificate manager is not included in "
                        + "this build. Trusted CA certificates live in appdb/_main.ks.", 0));
                }
            } else {
                shell.home().lock();
                shell.goHome();
            }
        }

        private void changeCode() {
            verify("Change security code", new Runnable() {
                public void run() {
                    shell.push(new CodeScreen("New code", "New security code:", true, new CodeListener() {
                        public boolean onCode(final String first) {
                            if (first.length() < 4) {
                                shell.info("Use 4 to 10 digits");
                                return false;
                            }
                            shell.later(new Runnable() {
                                public void run() {
                                    shell.push(new CodeScreen("New code", "Verify new code:", true,
                                        new CodeListener() {
                                            public boolean onCode(String second) {
                                                if (!second.equals(first)) {
                                                    return false;
                                                }
                                                Prefs.set(P_CODE, hash(first));
                                                shell.info("Security code changed");
                                                return true;
                                            }
                                        }));
                                }
                            }, 50);
                            return true;
                        }
                    }));
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*                           masked entry                             */
    /* ------------------------------------------------------------------ */

    interface CodeListener {
        /** true = accepted (the screen closes), false = "Code error". */
        boolean onCode(String code);
    }

    static class CodeScreen extends Screen {
        private static final int MAX = 10;

        private final String prompt;
        private final boolean cancellable;
        private final CodeListener listener;
        private final StringBuffer code = new StringBuffer();
        private String error;
        private int tries;

        CodeScreen(String title, String prompt, boolean cancellable, CodeListener l) {
            super(title);
            this.prompt = prompt;
            this.cancellable = cancellable;
            this.listener = l;
        }

        String softLeft() { return code.length() > 0 ? "OK" : null; }
        String softRight() { return code.length() > 0 ? "Clear" : (cancellable ? "Cancel" : null); }

        void paint(Graphics g, int x, int y, int w, int h) {
            g.setColor(Theme.C_TEXT);
            g.drawString(prompt, x + Theme.MARGIN + 4, y + 12, Graphics.TOP | Graphics.LEFT);
            int bx = x + Theme.MARGIN + 4, by = y + 12 + Theme.FONT_H + 6;
            int bw = w - 2 * (Theme.MARGIN + 4), bh = Theme.FONT_H + 10;
            g.setColor(Theme.C_BG);
            g.fillRect(bx, by, bw, bh);
            g.setColor(Theme.C_FRAME);
            g.drawRect(bx, by, bw - 1, bh - 1);
            StringBuffer stars = new StringBuffer();
            for (int i = 0; i < code.length(); i++) {
                stars.append('*');
            }
            g.setColor(Theme.C_TEXT);
            Theme.bold(g, stars.toString(), bx + 6, by + 5, Graphics.TOP | Graphics.LEFT);
            // cursor
            int cx = bx + 6 + Theme.FONT.stringWidth(stars.toString()) + 1;
            g.drawLine(cx, by + 4, cx, by + bh - 5);
            if (error != null) {
                g.setColor(0xC02020);
                g.drawString(error, x + w / 2, by + bh + 12, Graphics.TOP | Graphics.HCENTER);
            }
            if (!cancellable) {
                g.setColor(Theme.C_TEXT_DIM);
                String[] lines = Theme.wrap("Enter the security code to use the phone. "
                    + "The default code is 12345.", w - 16);
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(lines[i], x + w / 2, y + h - 20 - lines.length * Theme.FONT_H
                                 + i * Theme.FONT_H, Graphics.TOP | Graphics.HCENTER);
                }
            }
        }

        private void submit() {
            if (code.length() == 0) {
                return;
            }
            String c = code.toString();
            if (listener.onCode(c)) {
                if (shell.top() == this) {
                    shell.pop();
                }
            } else {
                tries++;
                error = "Code error";
                code.setLength(0);
                repaint();
            }
        }

        boolean key(int k) {
            if (Keymap.isDigit(k)) {
                if (code.length() < MAX) {
                    code.append((char) k);
                    error = null;
                }
                repaint();
                return true;
            }
            switch (k) {
            case Keymap.SELECT:
            case Keymap.SOFT_L:
                submit();
                return true;
            case Keymap.CLEAR:
                if (code.length() > 0) {
                    code.setLength(code.length() - 1);
                    repaint();
                }
                return true;
            case Keymap.SOFT_R:
                if (code.length() > 0) {
                    code.setLength(code.length() - 1);
                    repaint();
                } else if (cancellable) {
                    shell.pop();
                }
                return true;
            case Keymap.END:
                if (cancellable) {
                    return false;             // the shell goes home
                }
                return true;                  // locked: End does nothing
            default:
                return true;                  // swallow everything else
            }
        }

        void keyLong(int k) {
            if (k == Keymap.SOFT_R || k == Keymap.CLEAR) {
                code.setLength(0);
                repaint();
            }
        }
    }
}
