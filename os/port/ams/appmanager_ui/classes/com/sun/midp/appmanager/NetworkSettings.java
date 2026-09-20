/*
 * S100 shell - Settings > Network: SIM card management, Airplane mode,
 * VPN and Private DNS.
 *
 * Everything runs through /data/j2me/s100_net.sh (os/device). What the
 * JioPhone can do in Java mode, with KaiOS's Gecko stopped:
 *
 *   SIM card    rild keeps running; there is no RIL client here, so the
 *               SIM state comes from the modem's properties (last NITZ
 *               operator, PLMN) and the radio is switched through rild's
 *               debug socket (device/sockctl.c). A mobile data call can
 *               be set up the same way (experimental: the network gives
 *               an address to rmnet_data0, which is then made the default
 *               route with ndc).
 *   Airplane    radio off + Wi-Fi/hotspot/Bluetooth off, remembered in
 *               persist.radio.airplane_mode_on for KaiOS too.
 *   VPN         profiles are kept here; connecting needs Android's
 *               legacy VPN daemons. This build has racoon (IPsec) but no
 *               mtpd/pppd, so only IPsec Xauth can be attempted and
 *               PPTP/L2TP are refused with a note.
 *   Private DNS the phone's resolver servers (netd + net.dns*), chosen
 *               from the usual public providers or typed in. There is no
 *               DNS-over-TLS stub, so it is plain DNS to that provider.
 */

package com.sun.midp.appmanager;


class NetworkSettings {
    static final String P_APN = "net.apn";
    static final String P_DATA_SIM = "net.sim.data";           // 1 | 2
    static final String P_CALL_SIM = "net.sim.calls";
    static final String P_SMS_SIM = "net.sim.sms";
    static final String P_APM_WIFI = "net.apm.wifi";           // keep Wi-Fi in airplane mode
    static final String P_DNS_MODE = "net.dns.mode";           // provider key
    static final String P_DNS_1 = "net.dns.custom1";
    static final String P_DNS_2 = "net.dns.custom2";
    static final String P_VPN_COUNT = "vpn.count";

    static final String[] DNS_LABELS = {
        "Off (network's DNS)", "Google", "Cloudflare", "Quad9", "AdGuard", "OpenDNS", "Custom"
    };
    static final String[] DNS_KEYS = {"off", "google", "cloudflare", "quad9", "adguard", "opendns", "custom"};
    static final String[][] DNS_SERVERS = {
        {"", ""}, {"8.8.8.8", "8.8.4.4"}, {"1.1.1.1", "1.0.0.1"}, {"9.9.9.9", "149.112.112.112"},
        {"94.140.14.14", "94.140.15.15"}, {"208.67.222.222", "208.67.220.220"}, {"", ""}
    };
    static final String[] DNS_HOSTS = {
        "", "dns.google", "one.one.one.one", "dns.quad9.net", "dns.adguard-dns.com",
        "dns.opendns.com", ""
    };

    static final String[] VPN_TYPE_LABELS = {"IPsec Xauth PSK", "IPsec Xauth RSA", "L2TP/IPsec PSK", "PPTP"};
    static final String[] VPN_TYPE_KEYS = {"xauthpsk", "xauthrsa", "l2tp", "pptp"};
    static final String[] VPN_FIELDS = {"name", "type", "server", "user", "pass", "psk"};

    private final Shell shell;

    NetworkSettings(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("settings.network", "Network", "network", new MenuItem[] {
            new MenuItem("settings.network.sim", "SIM card management", "sim", new Runnable() {
                public void run() { shell.push(new SimScreen()); }
            }),
            new MenuItem("settings.network.airplane", "Airplane mode", "airplane", new Runnable() {
                public void run() { shell.push(new AirplaneScreen()); }
            }),
            new MenuItem("settings.network.vpn", "VPN", "vpn", new Runnable() {
                public void run() { shell.push(new VpnScreen()); }
            }),
            new MenuItem("settings.network.dns", "Private DNS", "dns", new Runnable() {
                public void run() { shell.push(new DnsScreen()); }
            }),
        });
    }

    /* ------------------------------------------------------------------ */
    /*                              plumbing                              */
    /* ------------------------------------------------------------------ */

    private String query(String args) {
        return Sys.exec(Sys.NET_SH + " " + args, 8000);
    }

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
        if (e == null && out != null && out.length() > 0 && out.length() < 160) {
            e = out;
        }
        shell.showPopup(Popup.info(e == null ? fallback : e, 0));
    }

    abstract class ValuesScreen extends ListScreen {
        ValuesScreen(String title) {
            super(title);
        }

        String softLeft() { return current() == null ? null : "Select"; }

        void choose(String title, String[] labels, int cur, final Popup.Listener l) {
            shell.showPopup(Popup.choice(title, labels, cur, l));
        }

        void reload() {
            refresh();
            clamp();
            repaint();
        }
    }

    static String pick(String[] a, int i, String def) {
        return (i >= 0 && i < a.length) ? a[i] : def;
    }

    /* ------------------------------------------------------------------ */
    /*                            SIM cards                               */
    /* ------------------------------------------------------------------ */

    class SimScreen extends ValuesScreen {
        private String status, data;
        private int slots = 1;

        SimScreen() {
            super("SIM card management");
        }

        void refresh() {
            status = query("sim status");
            data = query("data status");
            slots = (int) Sys.parseLong(Sys.field(status, "slots", "1"));
            if (slots < 1) {
                slots = 1;
            }
            items.removeAllElements();
            for (int i = 1; i <= slots; i++) {
                String op = Sys.field(status, "sim" + i + ".operator", "");
                String st = Sys.field(status, "sim" + i + ".state", "unknown");
                String v = op.length() > 0 ? op : simStateText(st);
                add(new Item("SIM " + i, new Integer(i))).value(v);
            }
            boolean radio = Sys.field(status, "radio", "on").equals("on");
            add(new Item("Radio", "radio")).value(radio ? "On" : "Off");
            boolean dataOn = Sys.field(data, "state", "").equals("connected");
            add(new Item("Mobile data", "data")).value(dataOn ? "Connected" : "Off");
            add(new Item("Access point (APN)", "apn")).value(Prefs.get(P_APN, "jionet"));
            if (slots > 1) {
                add(new Item("SIM for calls", "callsim")).value("SIM " + Prefs.get(P_CALL_SIM, "1"));
                add(new Item("SIM for messages", "smssim")).value("SIM " + Prefs.get(P_SMS_SIM, "1"));
                add(new Item("SIM for data", "datasim")).value("SIM " + Prefs.get(P_DATA_SIM, "1"));
            }
            add(new Item("PIN code request", "pin"));
            add(new Item("Details", "details"));
        }

        private String simStateText(String st) {
            if (st.equals("READY")) return "Ready";
            if (st.equals("ABSENT")) return "No SIM card";
            if (st.equals("PIN_REQUIRED")) return "PIN required";
            if (st.equals("PUK_REQUIRED")) return "PUK required";
            if (st.equals("NOT_READY")) return "Not ready";
            return "Inserted";
        }

        void select(Item it) {
            if (it.data instanceof Integer) {
                int i = ((Integer) it.data).intValue();
                shell.push(new TextViewScreen("SIM " + i,
                    "Operator: " + Sys.field(status, "sim" + i + ".operator", "-")
                    + "\nNetwork code: " + Sys.field(status, "sim" + i + ".plmn", "-")
                    + "\nSIM operator: " + Sys.field(status, "sim" + i + ".numeric", "-")
                    + "\nNetwork type: " + Sys.field(status, "sim" + i + ".type", "-")
                    + "\nState: " + simStateText(Sys.field(status, "sim" + i + ".state", "unknown"))
                    + "\n\nThe SIM's own state (PIN, contacts, messages) is read by "
                    + "KaiOS's radio layer, which is not running in Java mode; the "
                    + "operator shown is the last one the modem registered with."));
                return;
            }
            String what = (String) it.data;
            if (what.equals("radio")) {
                final boolean on = Sys.field(status, "radio", "on").equals("on");
                shell.showPopup(Popup.confirm(on ? "Switch the radio off?\n(no calls or data until it is on again)"
                                                 : "Switch the radio on?", new Popup.Listener() {
                    public void onResult(int r) {
                        if (r == 1) {
                            job(on ? "Switching radio off\u2026" : "Switching radio on\u2026",
                                on ? "radio off" : "radio on", 15000, new Sys.JobListener() {
                                public void onDone(String out, boolean ok) {
                                    if (!ok) {
                                        fail(out, "Could not switch the radio");
                                    }
                                    reload();
                                }
                            });
                        }
                    }
                }));
            } else if (what.equals("data")) {
                final boolean on = Sys.field(data, "state", "").equals("connected");
                if (on) {
                    job("Disconnecting\u2026", "data off", 15000, new Sys.JobListener() {
                        public void onDone(String out, boolean ok) { reload(); }
                    });
                } else {
                    shell.showPopup(Popup.confirm("Connect mobile data through "
                        + Prefs.get(P_APN, "jionet") + "?\n(experimental in Java mode)",
                        new Popup.Listener() {
                            public void onResult(int r) {
                                if (r == 1) {
                                    job("Connecting\u2026", "data on " + Sys.q(Prefs.get(P_APN, "jionet")),
                                        40000, new Sys.JobListener() {
                                        public void onDone(String out, boolean ok) {
                                            if (ok) {
                                                shell.info("Connected, IP " + Sys.field(out, "ip", "?"));
                                            } else {
                                                fail(out, "Could not connect");
                                            }
                                            reload();
                                        }
                                    });
                                }
                            }
                        }));
                }
            } else if (what.equals("apn")) {
                shell.push(new TextInputScreen("Access point name:", Prefs.get(P_APN, "jionet"),
                    TextInputScreen.TEXT, 40, "OK", new TextInputScreen.Listener() {
                        public void onText(String t) {
                            if (t.trim().length() > 0) {
                                Prefs.set(P_APN, t.trim());
                            }
                            reload();
                        }
                    }));
            } else if (what.endsWith("sim")) {
                final String pref = what.equals("callsim") ? P_CALL_SIM
                    : (what.equals("smssim") ? P_SMS_SIM : P_DATA_SIM);
                String[] labels = new String[slots];
                for (int i = 0; i < slots; i++) {
                    labels[i] = "SIM " + (i + 1);
                }
                choose(it.label, labels, (int) Sys.parseLong(Prefs.get(pref, "1")) - 1, new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(pref, String.valueOf(r + 1));
                        reload();
                    }
                });
            } else if (what.equals("pin")) {
                shell.showPopup(Popup.info("The SIM PIN is asked for by KaiOS when the phone "
                    + "starts. Changing the PIN request or the PIN itself needs the "
                    + "SIM toolkit of KaiOS: Settings > Privacy & Security > SIM security.", 0));
            } else {
                shell.push(new TextViewScreen("Details",
                    "SIM slots: " + slots
                    + "\nRadio: " + Sys.field(status, "radio", "-")
                    + "\nRIL daemon: " + Sys.field(status, "rild", "-")
                    + "\nRIL: " + Sys.field(status, "ril", "-")
                    + "\nBaseband: " + Sys.field(status, "baseband", "-")
                    + "\nSubscriptions: " + Sys.field(status, "subscription", "-")
                    + "\nEmergency numbers: " + Sys.field(status, "ecc", "-")
                    + "\nSIM powered in airplane mode: " + Sys.field(status, "apm_sim_powered", "-")
                    + "\n\nMobile data: " + Sys.field(data, "state", "-")
                    + "\nInterface: " + Sys.field(data, "iface", "-")
                    + "\nIP address: " + Sys.field(data, "ip", "-")));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                           airplane mode                            */
    /* ------------------------------------------------------------------ */

    class AirplaneScreen extends ValuesScreen {
        private String status;

        AirplaneScreen() {
            super("Airplane mode");
        }

        void refresh() {
            status = query("airplane status");
            boolean on = Sys.field(status, "state", "off").equals("on");
            items.removeAllElements();
            add(new Item("Airplane mode", "toggle")).value(on ? "On" : "Off");
            add(new Item("Keep Wi-Fi on", "wifi")).value(Prefs.getBool(P_APM_WIFI, false) ? "Yes" : "No");
            add(new Item("Radio", "info")).value(Sys.field(status, "radio", "-").equals("on") ? "On" : "Off");
            add(new Item("Wi-Fi", "info")).value(Sys.field(status, "wifi", "-").equals("on") ? "On" : "Off");
            add(new Item("Bluetooth", "info")).value(Sys.field(status, "bt", "").equals("on") ? "On" : "Off");
            add(new Item("Hotspot", "info")).value(Sys.field(status, "hotspot", "").equals("on") ? "On" : "Off");
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("toggle")) {
                final boolean on = Sys.field(status, "state", "off").equals("on");
                String args = on ? "airplane off"
                    : "airplane on " + (Prefs.getBool(P_APM_WIFI, false) ? "1" : "0");
                job(on ? "Leaving airplane mode\u2026" : "Entering airplane mode\u2026", args, 40000,
                    new Sys.JobListener() {
                        public void onDone(String out, boolean ok) {
                            if (!ok) {
                                fail(out, "Could not change airplane mode");
                            }
                            reload();
                        }
                    });
            } else if (what.equals("wifi")) {
                choose("Keep Wi-Fi on", new String[] {"No", "Yes"},
                       Prefs.getBool(P_APM_WIFI, false) ? 1 : 0, new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.setBool(P_APM_WIFI, r == 1);
                        reload();
                    }
                });
            } else {
                reload();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                                VPN                                 */
    /* ------------------------------------------------------------------ */

    /** A stored profile: vpn.<n>.<field>. */
    static class Vpn {
        int n;
        String name, type, server, user, pass, psk;

        static Vpn load(int n) {
            Vpn v = new Vpn();
            v.n = n;
            v.name = Prefs.get("vpn." + n + ".name", "VPN " + (n + 1));
            v.type = Prefs.get("vpn." + n + ".type", "xauthpsk");
            v.server = Prefs.get("vpn." + n + ".server", "");
            v.user = Prefs.get("vpn." + n + ".user", "");
            v.pass = Prefs.get("vpn." + n + ".pass", "");
            v.psk = Prefs.get("vpn." + n + ".psk", "");
            return v;
        }

        void save() {
            Prefs.set("vpn." + n + ".name", name);
            Prefs.set("vpn." + n + ".type", type);
            Prefs.set("vpn." + n + ".server", server);
            Prefs.set("vpn." + n + ".user", user);
            Prefs.set("vpn." + n + ".pass", pass);
            Prefs.set("vpn." + n + ".psk", psk);
        }

        String typeLabel() {
            return pick(VPN_TYPE_LABELS, SettingsMenu.indexOf(VPN_TYPE_KEYS, type), type);
        }
    }

    static int vpnCount() {
        return Prefs.getInt(P_VPN_COUNT, 0);
    }

    static void vpnRemove(int n) {
        int count = vpnCount();
        for (int i = n; i < count - 1; i++) {
            Vpn next = Vpn.load(i + 1);
            next.n = i;
            next.save();
        }
        for (int f = 0; f < VPN_FIELDS.length; f++) {
            Prefs.set("vpn." + (count - 1) + "." + VPN_FIELDS[f], null);
        }
        Prefs.set(P_VPN_COUNT, String.valueOf(Math.max(0, count - 1)));
    }

    class VpnScreen extends ListScreen {
        private String status;

        VpnScreen() {
            super("VPN");
            rowH = 34;
        }

        void refresh() {
            status = query("vpn status");
            items.removeAllElements();
            String st = Sys.field(status, "state", "disconnected");
            String cur = Sys.field(status, "name", "");
            add(new Item("Status", st.equals("connected") ? "Connected to " + cur
                : (st.equals("connecting") ? "Connecting to " + cur : "Not connected"), "status"));
            add(new Item("Add VPN", "New profile", "add"));
            int n = vpnCount();
            for (int i = 0; i < n; i++) {
                Vpn v = Vpn.load(i);
                Item it = new Item(v.name, v.typeLabel() + ", " + v.server, v);
                if (st.equals("connected") && v.name.equals(cur)) {
                    it.value = "connected";
                }
                add(it);
            }
        }

        String softLeft() { return current() == null ? null : "Options"; }

        String[] optionsMenu(Item it) {
            if (it.data instanceof Vpn) {
                return new String[] {"Connect", "Edit", "Delete", "Disconnect"};
            }
            if (it.data.equals("status")) {
                return new String[] {"Refresh", "Disconnect", "Details"};
            }
            return null;
        }

        void select(Item it) {
            if (it.data instanceof Vpn) {
                connect((Vpn) it.data);
            } else if (it.data.equals("add")) {
                Vpn v = new Vpn();
                v.n = vpnCount();
                v.name = "VPN " + (v.n + 1);
                v.type = "xauthpsk";
                v.server = v.user = v.pass = v.psk = "";
                shell.push(new VpnEditScreen(v, true));
            } else {
                refresh();
                repaint();
            }
        }

        void option(Item it, int r) {
            if (it.data instanceof Vpn) {
                final Vpn v = (Vpn) it.data;
                if (r == 0) {
                    connect(v);
                } else if (r == 1) {
                    shell.push(new VpnEditScreen(v, false));
                } else if (r == 2) {
                    shell.showPopup(Popup.confirm("Delete " + v.name + "?", new Popup.Listener() {
                        public void onResult(int r2) {
                            if (r2 == 1) {
                                vpnRemove(v.n);
                                refresh();
                                clamp();
                                repaint();
                            }
                        }
                    }));
                } else {
                    disconnect();
                }
            } else if (r == 0) {
                refresh();
                repaint();
            } else if (r == 1) {
                disconnect();
            } else {
                shell.push(new TextViewScreen("VPN details",
                    "State: " + Sys.field(status, "state", "-")
                    + "\nProfile: " + Sys.field(status, "name", "-")
                    + "\nServer: " + Sys.field(status, "server", "-")
                    + "\nInterface: " + Sys.field(status, "iface", "-")
                    + "\nAddress: " + Sys.field(status, "address", "-")
                    + "\nDNS: " + Sys.field(status, "dns", "-")
                    + "\n\nClients on this phone\n  racoon (IPsec): "
                    + (Sys.field(status, "racoon", "0").equals("1") ? "yes" : "no")
                    + "\n  mtpd (PPTP/L2TP): " + (Sys.field(status, "mtpd", "0").equals("1") ? "yes" : "no")
                    + "\n  pppd: " + (Sys.field(status, "pppd", "0").equals("1") ? "yes" : "no")
                    + "\n\nOnly IPsec Xauth can be attempted with racoon alone; PPTP "
                    + "and L2TP need mtpd, which this KaiOS build does not include."));
            }
        }

        private void connect(final Vpn v) {
            if (v.server.length() == 0) {
                shell.info("Set the server address first");
                return;
            }
            if (v.type.equals("pptp") || v.type.equals("l2tp")) {
                shell.showPopup(Popup.info(v.typeLabel() + " needs the mtpd service, which this "
                    + "phone's software does not include. Use an IPsec Xauth profile.", 0));
                return;
            }
            job("Connecting to " + v.name + "\u2026",
                "vpn connect " + v.type + " " + Sys.q(v.server) + " " + Sys.q(v.user) + " "
                + Sys.q(v.pass) + " " + Sys.q(v.psk) + " " + Sys.q(v.name), 60000,
                new Sys.JobListener() {
                    public void onDone(String out, boolean ok) {
                        if (ok) {
                            shell.info("Connected to " + v.name);
                        } else {
                            fail(out, "Could not connect to " + v.name);
                        }
                        refresh();
                        clamp();
                        repaint();
                    }
                });
        }

        private void disconnect() {
            job("Disconnecting\u2026", "vpn disconnect", 15000, new Sys.JobListener() {
                public void onDone(String out, boolean ok) {
                    refresh();
                    clamp();
                    repaint();
                }
            });
        }
    }

    class VpnEditScreen extends ListScreen {
        private final Vpn v;
        private final boolean isNew;

        VpnEditScreen(Vpn v, boolean isNew) {
            super(isNew ? "Add VPN" : "Edit VPN");
            this.v = v;
            this.isNew = isNew;
        }

        void refresh() {
            items.removeAllElements();
            add(new Item("Name", "name")).value(v.name);
            add(new Item("Type", "type")).value(v.typeLabel());
            add(new Item("Server address", "server")).value(v.server.length() == 0 ? "-" : v.server);
            add(new Item("Username", "user")).value(v.user.length() == 0 ? "-" : v.user);
            add(new Item("Password", "pass")).value(v.pass.length() == 0 ? "-" : "****");
            add(new Item(v.type.equals("xauthrsa") ? "Certificate" : "Pre-shared key", "psk"))
                .value(v.psk.length() == 0 ? "-" : "****");
            add(new Item("Save", "save"));
        }

        String softLeft() { return current() == null ? null : "Change"; }

        private void edit(final String field, String title, String initial, final boolean secret) {
            shell.push(new TextInputScreen(title, initial, TextInputScreen.TEXT, 64, "OK",
                new TextInputScreen.Listener() {
                    public void onText(String t) {
                        t = t.trim();
                        if (field.equals("name")) v.name = t.length() == 0 ? v.name : t;
                        else if (field.equals("server")) v.server = t;
                        else if (field.equals("user")) v.user = t;
                        else if (field.equals("pass")) v.pass = t;
                        else v.psk = t;
                        refresh();
                    }
                }));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("type")) {
                shell.showPopup(Popup.choice("Type", VPN_TYPE_LABELS,
                    SettingsMenu.indexOf(VPN_TYPE_KEYS, v.type), new Popup.Listener() {
                        public void onResult(int r) {
                            v.type = VPN_TYPE_KEYS[r];
                            refresh();
                            repaint();
                        }
                    }));
            } else if (what.equals("save")) {
                if (v.server.length() == 0) {
                    shell.info("Enter the server address");
                    return;
                }
                v.save();
                if (isNew) {
                    Prefs.set(P_VPN_COUNT, String.valueOf(v.n + 1));
                }
                shell.info("Saved");
                shell.pop();
            } else if (what.equals("name")) {
                edit("name", "Name:", v.name, false);
            } else if (what.equals("server")) {
                edit("server", "Server address:", v.server, false);
            } else if (what.equals("user")) {
                edit("user", "Username:", v.user, false);
            } else if (what.equals("pass")) {
                edit("pass", "Password:", v.pass, true);
            } else {
                edit("psk", v.type.equals("xauthrsa") ? "Certificate name:" : "Pre-shared key:", v.psk, true);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                            Private DNS                             */
    /* ------------------------------------------------------------------ */

    class DnsScreen extends ValuesScreen {
        private String status;

        DnsScreen() {
            super("Private DNS");
        }

        void refresh() {
            status = query("dns status");
            String mode = Prefs.get(P_DNS_MODE, "off");
            int mi = SettingsMenu.indexOf(DNS_KEYS, mode);
            items.removeAllElements();
            add(new Item("Private DNS", "mode")).value(pick(DNS_LABELS, mi, mode));
            if (mode.equals("custom")) {
                add(new Item("Server 1", "c1")).value(Prefs.get(P_DNS_1, "-"));
                add(new Item("Server 2", "c2")).value(Prefs.get(P_DNS_2, "-"));
            } else if (!mode.equals("off")) {
                add(new Item("Provider host", "host")).value(DNS_HOSTS[mi]);
                add(new Item("Servers", "host")).value(DNS_SERVERS[mi][0]);
            }
            add(new Item("In use", "status")).value(
                Sys.field(status, "mode", "auto").equals("custom") ? "Private" : "Network's");
            add(new Item("Apply now", "apply"));
            add(new Item("Details", "details"));
        }

        private void apply() {
            String mode = Prefs.get(P_DNS_MODE, "off");
            int mi = SettingsMenu.indexOf(DNS_KEYS, mode);
            String d1, d2, name;
            if (mode.equals("off")) {
                job("Restoring the network's DNS\u2026", "dns clear", 15000, new Sys.JobListener() {
                    public void onDone(String out, boolean ok) { reload(); }
                });
                return;
            }
            if (mode.equals("custom")) {
                d1 = Prefs.get(P_DNS_1, "");
                d2 = Prefs.get(P_DNS_2, "");
                name = "Custom";
            } else {
                d1 = DNS_SERVERS[mi][0];
                d2 = DNS_SERVERS[mi][1];
                name = DNS_LABELS[mi];
            }
            if (d1.length() == 0) {
                shell.info("Enter a server address first");
                return;
            }
            job("Applying DNS\u2026", "dns set " + Sys.q(name) + " " + Sys.q(d1) + " " + Sys.q(d2), 15000,
                new Sys.JobListener() {
                    public void onDone(String out, boolean ok) {
                        if (!ok) {
                            fail(out, "Could not set the DNS servers");
                        }
                        reload();
                    }
                });
        }

        private boolean validIp(String s) {
            String[] p = Sys.split(s, '.');
            if (p.length != 4) {
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (p[i].length() == 0 || p[i].length() > 3) {
                    return false;
                }
                for (int j = 0; j < p[i].length(); j++) {
                    if (!Character.isDigit(p[i].charAt(j))) {
                        return false;
                    }
                }
                if (Integer.parseInt(p[i]) > 255) {
                    return false;
                }
            }
            return true;
        }

        private void editServer(final String pref, String title) {
            shell.push(new TextInputScreen(title, Prefs.get(pref, ""), TextInputScreen.TEXT, 15, "OK",
                new TextInputScreen.Listener() {
                    public void onText(String t) {
                        t = t.trim();
                        if (t.length() > 0 && !validIp(t)) {
                            shell.info("Enter an IPv4 address (key 1 types the dot)");
                            return;
                        }
                        Prefs.set(pref, t.length() == 0 ? null : t);
                        reload();
                    }
                }));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("mode")) {
                choose("Private DNS", DNS_LABELS, SettingsMenu.indexOf(DNS_KEYS, Prefs.get(P_DNS_MODE, "off")),
                       new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(P_DNS_MODE, DNS_KEYS[r]);
                        reload();
                        if (!DNS_KEYS[r].equals("custom")) {
                            apply();
                        }
                    }
                });
            } else if (what.equals("c1")) {
                editServer(P_DNS_1, "Server 1:");
            } else if (what.equals("c2")) {
                editServer(P_DNS_2, "Server 2:");
            } else if (what.equals("apply")) {
                apply();
            } else if (what.equals("details")) {
                shell.push(new TextViewScreen("DNS details",
                    "Mode: " + Sys.field(status, "mode", "-")
                    + "\nChosen: " + Sys.field(status, "name", "-")
                    + "\n  " + Sys.field(status, "dns1", "-") + ", " + Sys.field(status, "dns2", "-")
                    + "\nFrom DHCP: " + Sys.field(status, "dhcp1", "-") + ", " + Sys.field(status, "dhcp2", "-")
                    + "\nIn use (net.dns): " + Sys.field(status, "net1", "-") + ", " + Sys.field(status, "net2", "-")
                    + "\n\nJava mode has no DNS-over-TLS client: the chosen provider "
                    + "is asked in the clear on port 53. The choice is applied again "
                    + "every time Wi-Fi connects, and to the hotspot's clients."));
            } else {
                reload();
            }
        }
    }
}
