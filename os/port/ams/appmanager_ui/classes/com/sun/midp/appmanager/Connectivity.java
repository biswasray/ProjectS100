/*
 * S100 shell - Settings > Connectivity: Bluetooth, Wi-Fi, Hotspot, USB.
 *
 * All of it is UI over /data/j2me/s100_net.sh (os/device), which drives
 * the phone's daemons: wpa_supplicant through wpa_cli, netd through ndc
 * (interfaces, soft AP, tethering), init through setprop (services, the
 * USB composition) - the Java runtime's root has no capabilities of its
 * own. Quick queries run synchronously (Sys.exec); anything that takes
 * seconds (scan, join, hotspot) is a background job behind a "please
 * wait" popup (Sys.run).
 *
 * Bluetooth is limited to powering the radio and naming the phone: the
 * Bluedroid stack lives inside KaiOS's bluetoothd, which only Gecko can
 * drive, so pairing is not available in Java mode.
 */

package com.sun.midp.appmanager;

import java.util.Vector;

class Connectivity {
    static final String P_HOTSPOT_SSID = "hotspot.ssid";
    static final String P_HOTSPOT_PSK = "hotspot.psk";
    static final String P_HOTSPOT_OPEN = "hotspot.open";        // 1 = no password
    static final String P_HOTSPOT_CHANNEL = "hotspot.channel";
    static final String P_BT_VISIBLE = "bt.visible";

    private final Shell shell;

    Connectivity(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("settings.connectivity", "Connectivity", "connectivity", new MenuItem[] {
            new MenuItem("settings.connectivity.bluetooth", "Bluetooth", "bluetooth", new Runnable() {
                public void run() { shell.push(new BluetoothScreen()); }
            }),
            new MenuItem("settings.connectivity.wifi", "Wi-Fi", "wifi", new Runnable() {
                public void run() { shell.push(new WifiScreen()); }
            }),
            new MenuItem("settings.connectivity.hotspot", "Hotspot", "hotspot", new Runnable() {
                public void run() { shell.push(new HotspotScreen()); }
            }),
            new MenuItem("settings.connectivity.usb", "USB", "usb", new Runnable() {
                public void run() { shell.push(new UsbScreen()); }
            }),
        });
    }

    /* ------------------------------------------------------------------ */
    /*                              plumbing                              */
    /* ------------------------------------------------------------------ */

    /** Quick query: the script's output, or null. */
    private String query(String args) {
        String out = Sys.exec(Sys.NET_SH + " " + args, 6000);
        return out;
    }

    /** Slow command behind a wait note; the listener runs on the event thread. */
    private void job(String note, String args, int timeoutMs, final Sys.JobListener l) {
        shell.showPopup(Popup.wait(note));
        Sys.run(shell, Sys.NET_SH + " " + args, timeoutMs, new Sys.JobListener() {
            public void onDone(String out, boolean ok) {
                shell.closePopup();
                l.onDone(out, ok);
            }
        });
    }

    private void fail(String out, String fallback) {
        String e = Sys.field(out, "error", null);
        if (e == null && out != null && out.length() > 0 && out.length() < 120) {
            e = out;
        }
        shell.showPopup(Popup.info(e == null ? fallback : e, 0));
    }

    /** A settings list whose rows carry a value on the right. */
    abstract class ValuesScreen extends ListScreen {
        ValuesScreen(String title) {
            super(title);
        }

        String softLeft() { return current() == null ? null : "Select"; }

        void choose(String title, String[] labels, int cur, final Popup.Listener l) {
            shell.showPopup(Popup.choice(title, labels, cur, new Popup.Listener() {
                public void onResult(int r) {
                    l.onResult(r);
                }
            }));
        }

        void reload() {
            refresh();
            clamp();
            repaint();
        }
    }

    /* ------------------------------------------------------------------ */
    /*                                Wi-Fi                               */
    /* ------------------------------------------------------------------ */

    class WifiScreen extends ValuesScreen {
        private String status;          // raw "wifi status" output

        WifiScreen() {
            super("Wi-Fi");
        }

        void refresh() {
            status = query("wifi status");
            String state = Sys.field(status, "state", "off");
            String ssid = Sys.field(status, "ssid", "");
            items.removeAllElements();
            add(new Item("Wi-Fi", "toggle")).value(state.equals("off") ? "Off" : "On");
            String st;
            if (state.equals("connected")) st = ssid;
            else if (state.equals("connecting")) st = "Connecting\u2026";
            else if (state.equals("hotspot")) st = "Hotspot on";
            else if (state.equals("off")) st = "Off";
            else st = "Not connected";
            add(new Item("Status", "status")).value(st);
            add(new Item("Available networks", "scan"));
            add(new Item("Saved networks", "saved"));
            add(new Item("Details", "details"));
        }

        void select(Item it) {
            String what = (String) it.data;
            String state = Sys.field(status, "state", "off");
            if (what.equals("toggle")) {
                final boolean on = state.equals("off");
                job(on ? "Turning Wi-Fi on\u2026" : "Turning Wi-Fi off\u2026",
                    on ? "wifi on" : "wifi off", 25000, new Sys.JobListener() {
                    public void onDone(String out, boolean ok) {
                        if (!ok) {
                            fail(out, "Could not change Wi-Fi");
                        }
                        reload();
                    }
                });
            } else if (what.equals("status")) {
                if (state.equals("connected")) {
                    shell.showPopup(Popup.menu("Wi-Fi", new String[] {"Refresh", "Disconnect"},
                        new Popup.Listener() {
                            public void onResult(int r) {
                                if (r == 0) {
                                    reload();
                                } else {
                                    job("Disconnecting\u2026", "wifi disconnect", 10000,
                                        new Sys.JobListener() {
                                            public void onDone(String out, boolean ok) { reload(); }
                                        });
                                }
                            }
                        }));
                } else {
                    reload();
                }
            } else if (what.equals("scan")) {
                if (state.equals("off") || state.equals("hotspot")) {
                    shell.info(state.equals("off") ? "Turn Wi-Fi on first" : "Turn the hotspot off first");
                    return;
                }
                shell.push(new NetworksScreen());
            } else if (what.equals("saved")) {
                if (state.equals("off") || state.equals("hotspot")) {
                    shell.info("Turn Wi-Fi on first");
                    return;
                }
                shell.push(new SavedScreen());
            } else {
                shell.push(new TextViewScreen("Wi-Fi details",
                    "State: " + state
                    + "\nNetwork: " + Sys.field(status, "ssid", "-")
                    + "\nIP address: " + Sys.field(status, "ip", "-")
                    + "\nGateway: " + Sys.field(status, "gateway", "-")
                    + "\nDNS: " + Sys.field(status, "dns", "-")
                    + "\nSignal: " + Sys.field(status, "signal", "-") + " dBm"
                    + "\nMAC address: " + Sys.field(status, "mac", "-")));
            }
        }
    }

    /** One scan result. */
    static class Net {
        String bssid, ssid, flags;
        int freq, signal;

        boolean secured() {
            return flags.indexOf("WPA") >= 0 || flags.indexOf("WEP") >= 0;
        }

        String security() {
            if (flags.indexOf("WPA2") >= 0) return "WPA2";
            if (flags.indexOf("WPA") >= 0) return "WPA";
            if (flags.indexOf("WEP") >= 0) return "WEP";
            return "Open";
        }

        String bars() {
            if (signal >= -55) return "Strong";
            if (signal >= -67) return "Good";
            if (signal >= -78) return "Fair";
            return "Weak";
        }
    }

    class NetworksScreen extends ListScreen {
        private boolean scanned;

        NetworksScreen() {
            super("Available networks");
            rowH = 34;
            emptyText = "No networks found";
        }

        void onShow() {
            super.onShow();
            if (!scanned) {
                scanned = true;
                scan();
            }
        }

        private void scan() {
            job("Searching for networks\u2026", "wifi scan", 20000, new Sys.JobListener() {
                public void onDone(String out, boolean ok) {
                    if (!ok) {
                        fail(out, "Scan failed");
                        return;
                    }
                    Vector nets = new Vector();
                    Vector lines = Sys.lines(out);
                    for (int i = 0; i < lines.size(); i++) {
                        String[] f = Sys.split((String) lines.elementAt(i), '\t');
                        if (f.length < 5 || f[0].indexOf(':') < 0) {
                            continue;
                        }
                        Net n = new Net();
                        n.bssid = f[0];
                        n.freq = (int) Sys.parseLong(f[1]);
                        n.signal = (int) Sys.parseLong(f[2]);
                        n.flags = f[3];
                        n.ssid = f[4].trim();
                        // one row per SSID, strongest access point wins
                        boolean dup = false;
                        for (int j = 0; j < nets.size(); j++) {
                            Net o = (Net) nets.elementAt(j);
                            if (o.ssid.equals(n.ssid) && n.ssid.length() > 0) {
                                dup = true;
                                if (n.signal > o.signal) {
                                    nets.setElementAt(n, j);
                                }
                            }
                        }
                        if (!dup) {
                            int at = 0;
                            while (at < nets.size() && ((Net) nets.elementAt(at)).signal >= n.signal) {
                                at++;
                            }
                            nets.insertElementAt(n, at);
                        }
                    }
                    items.removeAllElements();
                    for (int i = 0; i < nets.size(); i++) {
                        Net n = (Net) nets.elementAt(i);
                        add(new Item(n.ssid.length() == 0 ? "(hidden network)" : n.ssid,
                                     n.bars() + ", " + n.security() + ", " + n.signal + " dBm", n));
                    }
                    clamp();
                    repaint();
                }
            });
        }

        String softLeft() { return "Options"; }

        String[] optionsMenu(Item it) {
            return new String[] {"Connect", "Details", "Search again"};
        }

        boolean key(int k) {
            if (k == Keymap.SOFT_L && current() == null) {
                scan();
                return true;
            }
            return super.key(k);
        }

        void select(Item it) {
            connect((Net) it.data);
        }

        void option(Item it, int r) {
            Net n = (Net) it.data;
            if (r == 0) {
                connect(n);
            } else if (r == 1) {
                shell.push(new TextViewScreen(n.ssid,
                    "Security: " + n.security() + "\nSignal: " + n.signal + " dBm\nChannel: "
                    + channel(n.freq) + " (" + n.freq + " MHz)\nAccess point: " + n.bssid
                    + "\nFlags: " + n.flags));
            } else {
                scan();
            }
        }

        private int channel(int freq) {
            if (freq == 2484) return 14;
            if (freq >= 2412 && freq <= 2472) return (freq - 2407) / 5;
            if (freq >= 5000) return (freq - 5000) / 5;
            return 0;
        }

        private void connect(final Net n) {
            if (n.ssid.length() == 0) {
                shell.info("Hidden networks are not supported");
                return;
            }
            if (!n.secured()) {
                join(n.ssid, "");
                return;
            }
            shell.push(new TextInputScreen("Password for " + n.ssid + ":", null,
                TextInputScreen.TEXT, 63, "Connect", new TextInputScreen.Listener() {
                    public void onText(String t) {
                        join(n.ssid, t);
                    }
                }));
        }
    }

    /** Joins a network (adds/updates it in wpa_supplicant) and gets an address. */
    private void join(final String ssid, String psk) {
        job("Connecting to " + ssid + "\u2026",
            "wifi connect " + Sys.q(ssid) + " " + Sys.q(psk), 45000, new Sys.JobListener() {
            public void onDone(String out, boolean ok) {
                if (ok) {
                    shell.showPopup(Popup.info("Connected to " + ssid + "\nIP address "
                        + Sys.field(out, "ip", "?"), 2500));
                    Screen s = shell.top();
                    if (s instanceof NetworksScreen || s instanceof SavedScreen) {
                        shell.pop();
                    }
                    if (shell.top() instanceof WifiScreen) {
                        ((WifiScreen) shell.top()).reload();
                    }
                } else {
                    fail(out, "Could not connect to " + ssid);
                }
            }
        });
    }

    class SavedScreen extends ListScreen {
        SavedScreen() {
            super("Saved networks");
            emptyText = "No saved networks";
        }

        void refresh() {
            items.removeAllElements();
            String out = query("wifi saved");
            Vector lines = Sys.lines(out);
            for (int i = 0; i < lines.size(); i++) {
                String[] f = Sys.split((String) lines.elementAt(i), '\t');
                if (f.length < 2) {
                    continue;
                }
                Item it = new Item(f[1], f[1]);
                if (f.length >= 4 && f[3].indexOf("CURRENT") >= 0) {
                    it.value = "connected";
                } else if (f.length >= 4 && f[3].indexOf("DISABLED") >= 0) {
                    it.value = "disabled";
                }
                add(it);
            }
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Connect", "Forget"};
        }

        void select(Item it) {
            // the saved password is reused when none is given
            job("Connecting to " + it.label + "\u2026", "wifi connect " + Sys.q((String) it.data),
                45000, new Sys.JobListener() {
                public void onDone(String out, boolean ok) {
                    if (ok) {
                        shell.info("Connected, IP " + Sys.field(out, "ip", "?"));
                    } else {
                        fail(out, "Could not connect");
                    }
                    refresh();
                    clamp();
                    repaint();
                }
            });
        }

        void option(Item it, int r) {
            if (r == 0) {
                select(it);
            } else {
                clear(it);
            }
        }

        void clear(final Item it) {
            shell.showPopup(Popup.confirm("Forget " + it.label + "?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        query("wifi forget " + Sys.q((String) it.data));
                        refresh();
                        clamp();
                        repaint();
                    }
                }
            }));
        }
    }

    /* ------------------------------------------------------------------ */
    /*                               Hotspot                              */
    /* ------------------------------------------------------------------ */

    class HotspotScreen extends ValuesScreen {
        private String status;

        HotspotScreen() {
            super("Hotspot");
        }

        private String ssid() { return Prefs.get(P_HOTSPOT_SSID, "JioPhone S100"); }
        private String psk() { return Prefs.get(P_HOTSPOT_PSK, "12345678"); }
        private boolean open() { return Prefs.getBool(P_HOTSPOT_OPEN, false); }
        private String channel() { return Prefs.get(P_HOTSPOT_CHANNEL, "6"); }

        void refresh() {
            status = query("hotspot status");
            boolean on = Sys.field(status, "state", "off").equals("on");
            items.removeAllElements();
            add(new Item("Hotspot", "toggle")).value(on ? "On" : "Off");
            add(new Item("Network name", "ssid")).value(ssid());
            add(new Item("Security", "sec")).value(open() ? "Open" : "WPA2");
            add(new Item("Password", "psk")).value(open() ? "-" : psk());
            add(new Item("Channel", "chan")).value(channel());
            if (on) {
                add(new Item("Connected devices", "clients")).value(Sys.field(status, "clients", "0"));
                add(new Item("Address", "addr")).value(Sys.field(status, "address", "-"));
                String up = Sys.field(status, "upstream", "");
                add(new Item("Internet via", "up")).value(up.length() == 0 ? "none" : up);
            }
        }

        void select(Item it) {
            String what = (String) it.data;
            boolean on = Sys.field(status, "state", "off").equals("on");
            if (what.equals("toggle")) {
                if (on) {
                    job("Turning the hotspot off\u2026", "hotspot off", 30000, new Sys.JobListener() {
                        public void onDone(String out, boolean ok) { reload(); }
                    });
                } else {
                    shell.showPopup(Popup.confirm("Start hotspot " + ssid() + "?\nWi-Fi will be disconnected.",
                        new Popup.Listener() {
                            public void onResult(int r) {
                                if (r == 1) {
                                    start();
                                }
                            }
                        }));
                }
            } else if (on && !what.equals("clients")) {
                shell.info("Turn the hotspot off to change it");
            } else if (what.equals("ssid")) {
                shell.push(new TextInputScreen("Network name:", ssid(), TextInputScreen.TEXT, 32, "OK",
                    new TextInputScreen.Listener() {
                        public void onText(String t) {
                            if (t.trim().length() > 0) {
                                Prefs.set(P_HOTSPOT_SSID, t.trim());
                            }
                            reload();
                        }
                    }));
            } else if (what.equals("sec")) {
                choose("Security", new String[] {"WPA2", "Open"}, open() ? 1 : 0, new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.setBool(P_HOTSPOT_OPEN, r == 1);
                        reload();
                    }
                });
            } else if (what.equals("psk")) {
                shell.push(new TextInputScreen("Password (8+ characters):", psk(), TextInputScreen.TEXT, 63, "OK",
                    new TextInputScreen.Listener() {
                        public void onText(String t) {
                            if (t.length() < 8) {
                                shell.info("Use at least 8 characters");
                            } else {
                                Prefs.set(P_HOTSPOT_PSK, t);
                            }
                            reload();
                        }
                    }));
            } else if (what.equals("chan")) {
                final String[] ch = {"1", "6", "11"};
                choose("Channel", ch, SettingsMenu.indexOf(ch, channel()), new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(P_HOTSPOT_CHANNEL, ch[r]);
                        reload();
                    }
                });
            } else if (what.equals("clients")) {
                reload();
            }
        }

        private void start() {
            job("Starting hotspot\u2026", "hotspot on " + Sys.q(ssid()) + " "
                + Sys.q(open() ? "-" : psk()) + " " + channel(), 40000, new Sys.JobListener() {
                public void onDone(String out, boolean ok) {
                    if (!ok) {
                        fail(out, "Could not start the hotspot");
                    } else if (Sys.field(out, "upstream", "").length() == 0) {
                        shell.showPopup(Popup.info("Hotspot on.\nNo mobile data connection found: "
                            + "devices can join but will not reach the Internet.", 0));
                    }
                    reload();
                }
            });
        }
    }

    /* ------------------------------------------------------------------ */
    /*                                 USB                                */
    /* ------------------------------------------------------------------ */

    static final String[] USB_LABELS = {"Media transfer (MTP)", "Mass storage (memory card)", "Charging only"};
    static final String[] USB_KEYS = {"mtp", "mass_storage", "charging"};

    class UsbScreen extends ValuesScreen {
        private String status;

        UsbScreen() {
            super("USB");
        }

        void refresh() {
            status = query("usb status");
            String mode = Sys.field(status, "mode", "other");
            int i = SettingsMenu.indexOf(USB_KEYS, mode);
            items.removeAllElements();
            add(new Item("USB mode", "mode")).value(mode.equals("other")
                ? Sys.field(status, "config", "?") : shortLabel(i));
            add(new Item("USB debugging", "adb")).value(Sys.field(status, "adb", "0").equals("1") ? "On" : "Off");
            add(new Item("Cable", "cable")).value(
                Sys.field(status, "connected", "").equals("CONFIGURED") ? "Connected" : "Not connected");
            add(new Item("Details", "details"));
        }

        private String shortLabel(int i) {
            return i == 0 ? "MTP" : (i == 1 ? "Mass storage" : "Charging only");
        }

        private void apply(String mode, boolean adb) {
            job("Changing USB mode\u2026", "usb set " + mode + " " + (adb ? "1" : "0"), 15000,
                new Sys.JobListener() {
                    public void onDone(String out, boolean ok) {
                        if (!ok) {
                            fail(out, "Could not change the USB mode");
                        }
                        reload();
                    }
                });
        }

        void select(Item it) {
            String what = (String) it.data;
            final boolean adb = Sys.field(status, "adb", "0").equals("1");
            final String mode = Sys.field(status, "mode", "mtp");
            if (what.equals("mode")) {
                choose("USB mode", USB_LABELS, SettingsMenu.indexOf(USB_KEYS, mode), new Popup.Listener() {
                    public void onResult(int r) {
                        apply(USB_KEYS[r], adb);
                    }
                });
            } else if (what.equals("adb")) {
                choose("USB debugging", new String[] {"Off", "On"}, adb ? 1 : 0, new Popup.Listener() {
                    public void onResult(int r) {
                        if ((r == 1) != adb) {
                            apply(mode.equals("other") ? "mtp" : mode, r == 1);
                        }
                    }
                });
            } else if (what.equals("details")) {
                shell.push(new TextViewScreen("USB details",
                    "Composition: " + Sys.field(status, "config", "-")
                    + "\nActive: " + Sys.field(status, "state", "-")
                    + "\nCable: " + Sys.field(status, "connected", "-")
                    + "\n\nMTP file transfer needs KaiOS's media server, so a PC "
                    + "sees the files after leaving Java mode. Mass storage shares "
                    + "the memory card directly. USB debugging keeps adb "
                    + "available in every mode."));
            } else {
                reload();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                              Bluetooth                             */
    /* ------------------------------------------------------------------ */

    class BluetoothScreen extends ValuesScreen {
        private String status;

        BluetoothScreen() {
            super("Bluetooth");
        }

        void refresh() {
            status = query("bt status");
            boolean on = Sys.field(status, "state", "off").equals("on");
            items.removeAllElements();
            add(new Item("Bluetooth", "toggle")).value(on ? "On" : "Off");
            add(new Item("Phone name", "name")).value(Sys.field(status, "name", "JioPhone"));
            add(new Item("Visibility", "vis")).value(Prefs.getBool(P_BT_VISIBLE, true) ? "Shown to all" : "Hidden");
            add(new Item("Paired devices", "paired"));
            String addr = Sys.field(status, "address", "");
            if (addr.length() > 0) {
                add(new Item("Address", "addr")).value(addr);
            }
        }

        void select(Item it) {
            String what = (String) it.data;
            final boolean on = Sys.field(status, "state", "off").equals("on");
            if (what.equals("toggle")) {
                job(on ? "Turning Bluetooth off\u2026" : "Turning Bluetooth on\u2026",
                    on ? "bt off" : "bt on", 25000, new Sys.JobListener() {
                    public void onDone(String out, boolean ok) {
                        if (!ok) {
                            fail(out, "Could not change Bluetooth");
                        }
                        reload();
                    }
                });
            } else if (what.equals("name")) {
                shell.push(new TextInputScreen("Phone name:", Sys.field(status, "name", "JioPhone"),
                    TextInputScreen.TEXT, 32, "OK", new TextInputScreen.Listener() {
                        public void onText(String t) {
                            if (t.trim().length() > 0) {
                                query("bt name " + Sys.q(t.trim()));
                            }
                            reload();
                        }
                    }));
            } else if (what.equals("vis")) {
                choose("Visibility", new String[] {"Shown to all", "Hidden"},
                       Prefs.getBool(P_BT_VISIBLE, true) ? 0 : 1, new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.setBool(P_BT_VISIBLE, r == 0);
                        reload();
                    }
                });
            } else if (what.equals("paired")) {
                shell.showPopup(Popup.info("Pairing and file transfer need KaiOS's Bluetooth "
                    + "service, which is not running in Java mode. Java mode can only "
                    + "power the radio on and off.", 0));
            } else {
                reload();
            }
        }
    }
}
