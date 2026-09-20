/*
 * S100 shell - Settings > Location.
 *
 * The JioPhone's GPS (Qualcomm gpsone) is normally driven by KaiOS's
 * Gecko through the gps HAL, which is gone in Java mode. Qualcomm's HAL
 * test client garden_app is on the phone, though, and s100_loc.sh runs
 * it with NMEA output; PositionScreen tails that output once a second
 * and parses $GPGGA / $GPRMC / $GPGSA / $GPGSV itself (Sys.split, no
 * regex in CLDC). A fix is remembered as "last known position".
 *
 * Cell information (operator, network code) comes from the modem's
 * properties; there is no cell-id positioning without a data connection
 * and a lookup service.
 */

package com.sun.midp.appmanager;

import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;

class LocationSettings {
    static final String P_ON = "loc.on";
    static final String P_METHOD = "loc.method";         // 0 standalone, 1 assisted, 2 ms-assisted
    static final String P_FORMAT = "loc.format";         // dd | dms
    static final String P_TIMEOUT = "loc.timeout";       // seconds per session
    static final String P_LAST_LAT = "loc.last.lat";
    static final String P_LAST_LON = "loc.last.lon";
    static final String P_LAST_ALT = "loc.last.alt";
    static final String P_LAST_TIME = "loc.last.time";
    static final String P_LAST_ACC = "loc.last.acc";

    static final String[] METHOD_LABELS = {"GPS only (standalone)", "Assisted GPS (network)", "Network assisted"};
    static final String[] METHOD_KEYS = {"0", "1", "2"};
    static final String[] FORMAT_LABELS = {"Decimal degrees", "Degrees, minutes, seconds"};
    static final String[] FORMAT_KEYS = {"dd", "dms"};
    static final String[] TIMEOUT_LABELS = {"1 minute", "2 minutes", "5 minutes"};
    static final String[] TIMEOUT_KEYS = {"60", "120", "300"};

    private final Shell shell;

    LocationSettings(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("settings.location", "Location", "location", new Runnable() {
            public void run() { shell.push(new LocationScreen()); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*                              helpers                               */
    /* ------------------------------------------------------------------ */

    /** A fixed-point number with n decimals (no Formatter in CLDC). */
    static String fmt(double v, int decimals) {
        boolean neg = v < 0;
        if (neg) {
            v = -v;
        }
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long t = (long) (v * scale + 0.5);
        String frac = Long.toString(t % scale);
        while (frac.length() < decimals) {
            frac = "0" + frac;
        }
        return (neg ? "-" : "") + (t / scale) + (decimals > 0 ? "." + frac : "");
    }

    /** 12.971599 N  ->  "12.971599" or "12\u00b058'17.8\"N". */
    static String coord(double v, boolean lat) {
        if (Prefs.get(P_FORMAT, "dd").equals("dd")) {
            return fmt(v, 6);
        }
        char hemi = lat ? (v < 0 ? 'S' : 'N') : (v < 0 ? 'W' : 'E');
        if (v < 0) {
            v = -v;
        }
        int d = (int) v;
        double m = (v - d) * 60;
        int mi = (int) m;
        double s = (m - mi) * 60;
        return d + "\u00b0" + mi + "'" + fmt(s, 1) + "\"" + hemi;
    }

    static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** NMEA ddmm.mmmm + hemisphere -> signed degrees. */
    static double nmeaDeg(String v, String hemi) {
        if (v == null || v.length() < 3) {
            return 0;
        }
        int dot = v.indexOf('.');
        int split = (dot < 0 ? v.length() : dot) - 2;
        if (split <= 0) {
            return 0;
        }
        double deg = parseDouble(v.substring(0, split)) + parseDouble(v.substring(split)) / 60.0;
        if (hemi.equals("S") || hemi.equals("W")) {
            deg = -deg;
        }
        return deg;
    }

    /* ------------------------------------------------------------------ */
    /*                           the settings                             */
    /* ------------------------------------------------------------------ */

    class LocationScreen extends ListScreen {
        private String gps;

        LocationScreen() {
            super("Location");
        }

        void refresh() {
            gps = Sys.exec(Sys.LOC_SH + " gps status", 5000);
            items.removeAllElements();
            boolean on = Prefs.getBool(P_ON, false);
            add(new Item("Location", "toggle")).value(on ? "On" : "Off");
            add(new Item("My position", "pos"));
            String last = Prefs.get(P_LAST_LAT, null);
            add(new Item("Last known position", "last")).value(last == null ? "none"
                : Prefs.get(P_LAST_TIME, "saved"));
            add(new Item("Positioning method", "method")).value(
                METHOD_LABELS[SettingsMenu.indexOf(METHOD_KEYS, Prefs.get(P_METHOD, "1"))]);
            add(new Item("Search time", "timeout")).value(
                TIMEOUT_LABELS[SettingsMenu.indexOf(TIMEOUT_KEYS, Prefs.get(P_TIMEOUT, "120"))]);
            add(new Item("Position format", "format")).value(
                Prefs.get(P_FORMAT, "dd").equals("dd") ? "Decimal" : "Deg/min/sec");
            add(new Item("Cell network", "cell"));
            add(new Item("Details", "details"));
        }

        String softLeft() { return current() == null ? null : "Select"; }

        private void choose(String title, final String[] labels, final String[] keys, final String pref) {
            shell.showPopup(Popup.choice(title, labels, SettingsMenu.indexOf(keys, Prefs.get(pref, keys[0])),
                new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(pref, keys[r]);
                        refresh();
                        repaint();
                    }
                }));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("toggle")) {
                choose("Location", new String[] {"Off", "On"}, new String[] {"0", "1"}, P_ON);
            } else if (what.equals("pos")) {
                if (!Prefs.getBool(P_ON, false)) {
                    shell.info("Turn Location on first");
                    return;
                }
                if (!Sys.field(gps, "gps", "0").equals("1") && Sys.phoneRoot() != null) {
                    shell.showPopup(Popup.info("No GPS client on this phone (garden_app missing)", 0));
                    return;
                }
                shell.push(new PositionScreen());
            } else if (what.equals("last")) {
                String lat = Prefs.get(P_LAST_LAT, null);
                if (lat == null) {
                    shell.info("No position saved yet");
                    return;
                }
                shell.push(new TextViewScreen("Last known position",
                    "Latitude: " + coord(parseDouble(lat), true)
                    + "\nLongitude: " + coord(parseDouble(Prefs.get(P_LAST_LON, "0")), false)
                    + "\nAltitude: " + Prefs.get(P_LAST_ALT, "-") + " m"
                    + "\nAccuracy: about " + Prefs.get(P_LAST_ACC, "-") + " m"
                    + "\nTime: " + Prefs.get(P_LAST_TIME, "-")
                    + "\n\nmaps: " + lat + "," + Prefs.get(P_LAST_LON, "0")));
            } else if (what.equals("method")) {
                choose("Positioning method", METHOD_LABELS, METHOD_KEYS, P_METHOD);
            } else if (what.equals("timeout")) {
                choose("Search time", TIMEOUT_LABELS, TIMEOUT_KEYS, P_TIMEOUT);
            } else if (what.equals("format")) {
                choose("Position format", FORMAT_LABELS, FORMAT_KEYS, P_FORMAT);
            } else if (what.equals("cell")) {
                String c = Sys.exec(Sys.LOC_SH + " cell status", 5000);
                String plmn = Sys.field(c, "plmn", "");
                shell.push(new TextViewScreen("Cell network",
                    "Operator: " + Sys.field(c, "operator", "-")
                    + "\nNetwork code (MCC MNC): " + (plmn.length() == 0 ? "-" : plmn)
                    + "\nCountry: " + country(plmn)
                    + "\nNetwork type: " + Sys.field(c, "type", "-")
                    + "\nTime zone: " + Sys.field(c, "tz", "-")
                    + "\n\nThe cell the phone is camped on only tells the country "
                    + "and operator; positioning by cell id needs a lookup service "
                    + "and a data connection."));
            } else {
                shell.push(new TextViewScreen("Location details",
                    "GPS client: " + (Sys.field(gps, "gps", "0").equals("1") ? "garden_app" : "none")
                    + "\nGPS enabled: " + Sys.field(gps, "enabled", "-")
                    + "\nAssisted GPS: " + Sys.field(gps, "agps", "-")
                    + "\nLocation service: " + Sys.field(gps, "loc", "-")
                    + "\nNetwork provider: " + Sys.field(gps, "nlp", "-")
                    + "\nSUPL server: " + Sys.field(gps, "supl", "-")
                    + "\nXTRA server: " + Sys.field(gps, "xtra", "-")
                    + "\nSession running: " + Sys.field(gps, "running", "0")
                    + "\n\nIn Java mode the receiver is driven by Qualcomm's HAL test "
                    + "client. Assisted modes need a data connection to the SUPL "
                    + "server; standalone GPS works without one but takes longer "
                    + "for the first fix (go outdoors)."));
            }
        }

        private String country(String plmn) {
            if (plmn.startsWith("404") || plmn.startsWith("405") || plmn.startsWith("406")) return "India";
            if (plmn.startsWith("310") || plmn.startsWith("311") || plmn.startsWith("312")) return "United States";
            if (plmn.startsWith("234") || plmn.startsWith("235")) return "United Kingdom";
            if (plmn.startsWith("262")) return "Germany";
            if (plmn.startsWith("208")) return "France";
            if (plmn.startsWith("470")) return "Bangladesh";
            if (plmn.startsWith("410")) return "Pakistan";
            if (plmn.startsWith("413")) return "Sri Lanka";
            if (plmn.startsWith("429")) return "Nepal";
            if (plmn.startsWith("424")) return "United Arab Emirates";
            if (plmn.startsWith("525")) return "Singapore";
            if (plmn.startsWith("502")) return "Malaysia";
            if (plmn.startsWith("460")) return "China";
            if (plmn.startsWith("440") || plmn.startsWith("441")) return "Japan";
            return plmn.length() >= 3 ? "MCC " + plmn.substring(0, 3) : "-";
        }
    }

    /* ------------------------------------------------------------------ */
    /*                          live positioning                          */
    /* ------------------------------------------------------------------ */

    class PositionScreen extends Screen {
        private static final String OUT = Sys.TMP + "/gps.nmea";

        private int pid = -1;
        private TimerTask ticker;
        private long started;
        private int seconds;

        /* parsed state */
        private boolean valid;
        private double lat, lon, alt, hdop, speedKmh, course;
        private int fixQuality, satsUsed, satsView, fixMode;
        private String utc = "";
        private int sentences;
        private String note;
        private boolean finished;

        PositionScreen() {
            super("My position");
        }

        String softLeft() { return "Options"; }
        String softRight() { return "Stop"; }

        void onShow() {
            if (pid > 0 || finished) {
                return;
            }
            start();
        }

        private void start() {
            Sys.mkdirs(Sys.TMP);
            Sys.remove(OUT);
            seconds = Prefs.getInt(P_TIMEOUT, 120);
            valid = false;
            finished = false;
            sentences = 0;
            note = null;
            started = System.currentTimeMillis();
            pid = Sys.spawn(Sys.LOC_SH + " gps start " + seconds + " " + Prefs.get(P_METHOD, "1")
                            + " " + Sys.q(OUT));
            if (ticker != null) {
                ticker.cancel();
            }
            ticker = shell.every(new Runnable() {
                public void run() { poll(); }
            }, 1000);
            repaint();
        }

        private void stopSession() {
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
            if (pid > 0) {
                Sys.kill(pid);
                pid = -1;
                Sys.exec(Sys.LOC_SH + " gps stop", 3000);
            }
        }

        void onHide() {
            stopSession();
        }

        void back() {
            stopSession();
            shell.pop();
        }

        private void poll() {
            String tail = Sys.exec("tail -c 6000 " + Sys.q(OUT), 2000);
            if (tail != null) {
                parse(tail);
            }
            if (pid > 0 && !Sys.alive(pid)) {
                pid = -1;
                finished = true;
                if (ticker != null) {
                    ticker.cancel();
                    ticker = null;
                }
                if (!valid) {
                    note = sentences == 0 ? "The receiver gave no data.\nSee Options > Raw output."
                        : "No fix within " + seconds + " s.\nGo outdoors with a clear view of the sky.";
                }
            }
            repaint();
        }

        private void parse(String text) {
            Vector lines = Sys.lines(text);
            // the first line of a tail may be cut: skip it unless it starts with $
            for (int i = 0; i < lines.size(); i++) {
                String l = (String) lines.elementAt(i);
                int d = l.indexOf('$');
                if (d < 0) {
                    continue;
                }
                l = l.substring(d);
                int star = l.indexOf('*');
                if (star > 0) {
                    l = l.substring(0, star);
                }
                String[] f = Sys.split(l, ',');
                if (f.length < 2 || f[0].length() < 6) {
                    continue;
                }
                String type = f[0].substring(3);
                try {
                    if (type.equals("GGA") && f.length >= 10) {
                        sentences++;
                        int q = (int) Sys.parseLong(f[6]);
                        fixQuality = q;
                        if (f[1].length() >= 6) {
                            utc = f[1].substring(0, 2) + ":" + f[1].substring(2, 4) + ":" + f[1].substring(4, 6);
                        }
                        satsUsed = (int) Sys.parseLong(f[7]);
                        if (q > 0 && f[2].length() > 0) {
                            lat = nmeaDeg(f[2], f[3]);
                            lon = nmeaDeg(f[4], f[5]);
                            hdop = parseDouble(f[8]);
                            alt = parseDouble(f[9]);
                            if (!valid) {
                                remember();
                            }
                            valid = true;
                        }
                    } else if (type.equals("RMC") && f.length >= 9) {
                        sentences++;
                        if (f[2].equals("A") && f[3].length() > 0) {
                            lat = nmeaDeg(f[3], f[4]);
                            lon = nmeaDeg(f[5], f[6]);
                            speedKmh = parseDouble(f[7]) * 1.852;
                            course = parseDouble(f[8]);
                            if (!valid) {
                                remember();
                            }
                            valid = true;
                        }
                    } else if (type.equals("GSA") && f.length >= 3) {
                        fixMode = (int) Sys.parseLong(f[2]);
                    } else if (type.equals("GSV") && f.length >= 4) {
                        satsView = (int) Sys.parseLong(f[3]);
                    }
                } catch (Exception e) {
                    // a torn sentence: ignore
                }
            }
        }

        private void remember() {
            Prefs.set(P_LAST_LAT, fmt(lat, 6));
            Prefs.set(P_LAST_LON, fmt(lon, 6));
            Prefs.set(P_LAST_ALT, fmt(alt, 0));
            Prefs.set(P_LAST_ACC, fmt(accuracy(), 0));
            Prefs.set(P_LAST_TIME, Clock.shortDate(Clock.now()) + " " + Clock.time());
        }

        private double accuracy() {
            return (hdop > 0 ? hdop : 5) * 5.0;       // rough: 5 m per HDOP unit
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            int ty = y + 6;
            int lx = x + Theme.MARGIN + 2;
            long secs = (System.currentTimeMillis() - started) / 1000;
            g.setColor(Theme.C_TEXT);
            String st;
            if (valid) {
                st = fixMode == 3 ? "3D fix" : (fixMode == 2 ? "2D fix" : "Fix");
            } else if (finished) {
                st = "Stopped";
            } else {
                st = "Searching\u2026 " + secs + " s";
            }
            Theme.bold(g, st, lx, ty, Graphics.TOP | Graphics.LEFT);
            g.drawString("Satellites: " + satsUsed + "/" + satsView, x + w - Theme.MARGIN - 2, ty,
                         Graphics.TOP | Graphics.RIGHT);
            ty += Theme.FONT_H + 4;
            g.setColor(Theme.C_SOFT_LINE);
            g.drawLine(lx, ty, x + w - Theme.MARGIN, ty);
            ty += 4;
            if (note != null && !valid) {
                String[] lines = Theme.wrap(note, w - 16);
                g.setColor(Theme.C_TEXT);
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(lines[i], x + w / 2, ty + i * Theme.FONT_H, Graphics.TOP | Graphics.HCENTER);
                }
                return;
            }
            String[][] rows = {
                {"Latitude", valid ? coord(lat, true) : "-"},
                {"Longitude", valid ? coord(lon, false) : "-"},
                {"Altitude", valid ? fmt(alt, 0) + " m" : "-"},
                {"Accuracy", valid ? "about " + fmt(accuracy(), 0) + " m" : "-"},
                {"Speed", valid ? fmt(speedKmh, 1) + " km/h" : "-"},
                {"Heading", valid ? fmt(course, 0) + "\u00b0" : "-"},
                {"Time (UTC)", utc.length() > 0 ? utc : "-"},
                {"Method", METHOD_LABELS[SettingsMenu.indexOf(METHOD_KEYS, Prefs.get(P_METHOD, "1"))]},
            };
            for (int i = 0; i < rows.length; i++) {
                g.setColor(Theme.C_TEXT_DIM);
                g.drawString(rows[i][0], lx, ty, Graphics.TOP | Graphics.LEFT);
                g.setColor(Theme.C_TEXT);
                g.drawString(Theme.fit(rows[i][1], w - 100), x + w - Theme.MARGIN - 2, ty,
                             Graphics.TOP | Graphics.RIGHT);
                ty += Theme.FONT_H + 2;
            }
            if (!valid && !finished) {
                ty += 6;
                g.setColor(Theme.C_TEXT_DIM);
                String[] hint = Theme.wrap("First fix can take a minute or two outdoors.", w - 16);
                for (int i = 0; i < hint.length; i++) {
                    g.drawString(hint[i], x + w / 2, ty + i * Theme.FONT_H, Graphics.TOP | Graphics.HCENTER);
                }
            }
        }

        boolean key(int k) {
            if (k == Keymap.SOFT_L || k == Keymap.SELECT) {
                options();
                return true;
            }
            return false;
        }

        private void options() {
            final Vector o = new Vector();
            o.addElement(finished ? "Search again" : "Restart search");
            if (valid) {
                o.addElement("Save as last position");
                o.addElement("Copy to Notes");
            }
            o.addElement("Raw output");
            shell.showPopup(Popup.menu("Options", o, new Popup.Listener() {
                public void onResult(int r) {
                    String what = (String) o.elementAt(r);
                    if (what.endsWith("search")) {
                        stopSession();
                        start();
                    } else if (what.startsWith("Save")) {
                        remember();
                        shell.info("Position saved");
                    } else if (what.startsWith("Copy")) {
                        Organiser.Note n = new Organiser.Note();
                        n.text = "Position " + Clock.shortDate(Clock.now()) + " " + Clock.time()
                            + "\n" + fmt(lat, 6) + ", " + fmt(lon, 6) + "\nalt " + fmt(alt, 0) + " m";
                        Organiser.saveNote(n);
                        shell.info("Saved in Notes");
                    } else {
                        String raw = Sys.exec("tail -c 3000 " + Sys.q(OUT), 2000);
                        shell.push(new TextViewScreen("Raw output",
                            (raw == null || raw.length() == 0) ? "(nothing yet)" : raw));
                    }
                }
            }));
        }
    }
}
