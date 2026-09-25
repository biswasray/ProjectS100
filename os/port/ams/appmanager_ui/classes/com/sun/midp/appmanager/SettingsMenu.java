/*
 * S100 shell - Settings: profiles, display, connectivity (Connectivity.java),
 * network (NetworkSettings.java: SIM, airplane mode, VPN, private DNS),
 * date and time, shortcuts, location (LocationSettings.java), security
 * (SecuritySettings.java), phone info, factory reset and the way out to
 * KaiOS.
 *
 * Values are stored with Prefs. The time zone is also written to
 * appdb/s100_tz.txt so that j2me.sh can export TZ for the whole VM on
 * the next start (MIDlets then see the same local time).
 */

package com.sun.midp.appmanager;

import java.util.Vector;

class SettingsMenu {
    static final String[] PROFILES = {"General", "Silent", "Meeting", "Outdoor"};
    static final String[] MENU_VIEWS = {"Grid", "List"};
    static final String[] WALLPAPERS = {"Blue", "Dark", "Plain"};
    static final String[] WALLPAPER_KEYS = {"blue", "dark", "plain"};
    static final String[] CLOCK_FORMATS = {"24-hour", "12-hour"};
    static final String P_SCREEN_TIMEOUT = "display.timeout";   // seconds, 0 = never
    static final String[] TIMEOUT_LABELS = {"15 seconds", "30 seconds", "1 minute",
                                            "2 minutes", "5 minutes", "10 minutes", "Never"};
    static final String[] TIMEOUT_KEYS = {"15", "30", "60", "120", "300", "600", "0"};

    private final Shell shell;

    SettingsMenu(Shell shell) {
        this.shell = shell;
    }

    MenuItem[] items() {
        return new MenuItem[] {
            new MenuItem("settings.profiles", "Profiles", "profile", new Runnable() {
                public void run() { profiles(); }
            }),
            new MenuItem("settings.display", "Display", "display", new Runnable() {
                public void run() { shell.push(new DisplayScreen()); }
            }),
            shell.menus.connectivity.item(),
            shell.menus.network.item(),
            new MenuItem("settings.time", "Date and time", "clock", new Runnable() {
                public void run() { shell.push(new TimeScreen()); }
            }),
            new MenuItem("settings.shortcuts", "My shortcuts", "shortcuts", new Runnable() {
                public void run() { shell.push(new ShortcutsScreen()); }
            }),
            shell.menus.location.item(),
            shell.menus.security.item(),
            new MenuItem("settings.phone", "Phone", "phone", new Runnable() {
                public void run() { shell.push(new PhoneScreen()); }
            }),
            new MenuItem("settings.exit", "Restart phone", "exit", new Runnable() {
                public void run() {
                    shell.showPopup(Popup.confirm("Restart the phone?",
                        new Popup.Listener() {
                            public void onResult(int r) {
                                if (r == 1) {
                                    Sys.power("reboot");
                                    shell.ams.shutdown();
                                }
                            }
                        }));
                }
            }),
        };
    }

    static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    /** Idle seconds before the backlight goes off, 0 = never. */
    static int screenTimeoutSeconds() {
        return Prefs.getInt(P_SCREEN_TIMEOUT, 30);
    }

    private void profiles() {
        int cur = indexOf(PROFILES, Prefs.get(Prefs.PROFILE, "General"));
        shell.showPopup(Popup.choice("Profiles", PROFILES, cur, new Popup.Listener() {
            public void onResult(int r) {
                Prefs.set(Prefs.PROFILE, PROFILES[r]);
                shell.info(PROFILES[r] + " activated");
            }
        }));
    }

    /** A settings list whose rows show their current value on the right. */
    abstract class ValuesScreen extends ListScreen {
        ValuesScreen(String title) {
            super(title);
        }

        String softLeft() { return "Change"; }

        void choose(String title, final String[] labels, int current, final Popup.Listener l) {
            shell.showPopup(Popup.choice(title, labels, current, new Popup.Listener() {
                public void onResult(int r) {
                    l.onResult(r);
                    refresh();
                    repaint();
                }
            }));
        }
    }

    class DisplayScreen extends ValuesScreen {
        DisplayScreen() {
            super("Display");
        }

        void refresh() {
            items.removeAllElements();
            String view = Prefs.get(Prefs.MENU_VIEW, "grid");
            add(new Item("Menu view", "view")).value(view.equals("grid") ? "Grid" : "List");
            String wp = Prefs.get(Prefs.WALLPAPER, "blue");
            add(new Item("Wallpaper", "wallpaper"))
                .value(WALLPAPERS[indexOf(WALLPAPER_KEYS, wp)]);
            add(new Item("Idle screen text", "operator")).value(Prefs.get(Prefs.OPERATOR, "JioPhone"));
            add(new Item("Screen timeout", "timeout")).value(
                TIMEOUT_LABELS[indexOf(TIMEOUT_KEYS, String.valueOf(screenTimeoutSeconds()))]);
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("view")) {
                choose("Menu view", MENU_VIEWS,
                       Prefs.get(Prefs.MENU_VIEW, "grid").equals("grid") ? 0 : 1,
                       new Popup.Listener() {
                           public void onResult(int r) {
                               Prefs.set(Prefs.MENU_VIEW, r == 0 ? "grid" : "list");
                           }
                       });
            } else if (what.equals("wallpaper")) {
                choose("Wallpaper", WALLPAPERS,
                       indexOf(WALLPAPER_KEYS, Prefs.get(Prefs.WALLPAPER, "blue")),
                       new Popup.Listener() {
                           public void onResult(int r) {
                               Prefs.set(Prefs.WALLPAPER, WALLPAPER_KEYS[r]);
                           }
                       });
            } else if (what.equals("timeout")) {
                choose("Screen timeout", TIMEOUT_LABELS,
                       indexOf(TIMEOUT_KEYS, String.valueOf(screenTimeoutSeconds())),
                       new Popup.Listener() {
                           public void onResult(int r) {
                               Prefs.set(P_SCREEN_TIMEOUT, TIMEOUT_KEYS[r]);
                           }
                       });
            } else {
                shell.push(new TextInputScreen("Idle screen text:", Prefs.get(Prefs.OPERATOR, "JioPhone"),
                    TextInputScreen.TEXT, 20, "OK", new TextInputScreen.Listener() {
                        public void onText(String t) {
                            Prefs.set(Prefs.OPERATOR, t.trim());
                        }
                    }));
            }
        }
    }

    class TimeScreen extends ValuesScreen {
        TimeScreen() {
            super("Date and time");
        }

        void refresh() {
            items.removeAllElements();
            add(new Item("Time", "now")).value(Clock.time());
            add(new Item("Date", "date")).value(Clock.shortDate(Clock.now()));
            add(new Item("Time format", "fmt")).value(Clock.is24h() ? "24-hour" : "12-hour");
            add(new Item("Time zone", "tz")).value(
                Clock.zoneLabel(Prefs.get(Prefs.TIMEZONE, "")));
        }

        String softLeft() {
            Item it = current();
            return (it != null && (it.data.equals("fmt") || it.data.equals("tz"))) ? "Change" : null;
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("fmt")) {
                choose("Time format", CLOCK_FORMATS, Clock.is24h() ? 0 : 1, new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.setBool(Prefs.CLOCK_24H, r == 0);
                    }
                });
            } else if (what.equals("tz")) {
                String[] labels = new String[Clock.ZONE_IDS.length];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = Clock.zoneLabel(Clock.ZONE_IDS[i]);
                }
                choose("Time zone", labels, indexOf(Clock.ZONE_IDS, Prefs.get(Prefs.TIMEZONE, "")),
                       new Popup.Listener() {
                           public void onResult(int r) {
                               String id = Clock.ZONE_IDS[r];
                               Prefs.set(Prefs.TIMEZONE, id);
                               // for j2me.sh (export TZ) on the next start
                               SysInfo.writeFile(SysInfo.storageDir() + "s100_tz.txt", id);
                           }
                       });
            } else if (what.equals("now") || what.equals("date")) {
                shell.info("The clock is set by the phone");
            }
        }
    }

    class ShortcutsScreen extends ValuesScreen {
        ShortcutsScreen() {
            super("My shortcuts");
        }

        void refresh() {
            items.removeAllElements();
            for (int i = 0; i < Keymap.SHORTCUT_KEYS.length; i++) {
                String id = Keymap.shortcut(Keymap.SHORTCUT_KEYS[i]);
                MenuItem mi = shell.menus.find(id);
                add(new Item(Keymap.SHORTCUT_NAMES[i], new Integer(i)))
                    .value(mi == null ? "-" : mi.label);
            }
        }

        void select(Item it) {
            final int slot = ((Integer) it.data).intValue();
            final Vector leaves = shell.menus.leaves();
            String[] labels = new String[leaves.size()];
            int cur = 0;
            String curId = Keymap.shortcut(Keymap.SHORTCUT_KEYS[slot]);
            for (int i = 0; i < labels.length; i++) {
                MenuItem mi = (MenuItem) leaves.elementAt(i);
                labels[i] = shell.menus.label(mi.id);
                if (mi.id.equals(curId)) {
                    cur = i;
                }
            }
            choose(Keymap.SHORTCUT_NAMES[slot], labels, cur, new Popup.Listener() {
                public void onResult(int r) {
                    Keymap.setShortcut(slot, ((MenuItem) leaves.elementAt(r)).id);
                }
            });
        }
    }

    class PhoneScreen extends ListScreen {
        PhoneScreen() {
            super("Phone");
        }

        void refresh() {
            items.removeAllElements();
            add(new Item("Phone info", "info"));
            add(new Item("Memory status", "memory"));
            add(new Item("Key map", "keymap"));
            add(new Item("Restore factory settings", "reset"));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("info")) {
                shell.push(new TextViewScreen("Phone info",
                    "S100 shell (Series 40 style)\n\n"
                    + "Runtime: " + SysInfo.platform() + "\n"
                    + SysInfo.profiles() + "\n"
                    + "Hardware: " + SysInfo.hardware() + "\n"
                    + "Display: " + Theme.W + "x" + Theme.H + "\n\n"
                    + "phoneME on the JioPhone framebuffer\n"
                    + "(os/ in ProjectS100)"));
            } else if (what.equals("memory")) {
                shell.push(new TextViewScreen("Memory status",
                    "Java heap\n"
                    + "  free:  " + SysInfo.kb(SysInfo.freeMemory()) + "\n"
                    + "  total: " + SysInfo.kb(SysInfo.totalMemory()) + "\n\n"
                    + "Contacts: " + shell.menus.contacts.all().size() + "\n"
                    + "Messages: " + (shell.menus.messaging.count(Messaging.INBOX)
                                      + shell.menus.messaging.count(Messaging.DRAFTS)
                                      + shell.menus.messaging.count(Messaging.OUTBOX)
                                      + shell.menus.messaging.count(Messaging.SENT)) + "\n"
                    + "Applications: " + shell.ams.userSuiteCount()));
            } else if (what.equals("keymap")) {
                shell.push(new TextViewScreen("Key map",
                    "Idle screen\n"
                    + "  Centre: Menu\n"
                    + "  Left soft: Go to\n"
                    + "  Right soft: Names\n"
                    + "  Call: Dialled numbers\n"
                    + "  0-9 * #: Dialer\n"
                    + "  Hold #: Silent on/off\n"
                    + "  Hold End: Switch off\n"
                    + "  Menu then *: Lock keypad\n"
                    + "  Up/Down/Left/Right: My shortcuts\n\n"
                    + "Everywhere\n"
                    + "  End: back to idle screen\n"
                    + "  End in an app: close it\n"
                    + "  1-9 in menus: open item\n\n"
                    + "Text entry\n"
                    + "  2-9: letters, hold: digit\n"
                    + "  1: punctuation  0: space\n"
                    + "  #: Abc/abc/ABC/123  *: symbols\n"
                    + "  Right soft: clear, hold: clear all\n\n"
                    + "Hardware codes: /data/j2me/keymap.txt"));
            } else if (what.equals("reset")) {
                shell.showPopup(Popup.confirm("Restore factory settings?\n(contacts and messages are kept)",
                    new Popup.Listener() {
                        public void onResult(int r) {
                            if (r == 1) {
                                Prefs.reset();
                                shell.info("Settings restored");
                            }
                        }
                    }));
            }
        }
    }
}
