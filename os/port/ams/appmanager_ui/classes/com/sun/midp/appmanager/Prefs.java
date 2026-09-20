/*
 * S100 shell - persistent key/value settings.
 *
 * One RMS record store ("s100_prefs") of the internal suite holding a
 * single record with "key=value" lines. Read once, written on every set().
 */

package com.sun.midp.appmanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Enumeration;
import java.util.Hashtable;
import javax.microedition.rms.RecordStore;

final class Prefs {
    private static final String STORE = "s100_prefs";
    private static Hashtable values;

    /** Well known keys. */
    static final String MENU_VIEW = "menu.view";          // grid | list
    static final String CLOCK_24H = "clock.24h";          // 1 | 0
    static final String PROFILE = "profile";              // General | Silent | Meeting | Outdoor
    static final String OPERATOR = "operator";            // idle screen label
    static final String WALLPAPER = "wallpaper";          // blue | dark | plain
    static final String TIMEZONE = "tz";                  // e.g. GMT+5:30
    static final String KEYLOCK_ON = "keylock";           // 1 | 0 (auto keyguard)
    static final String FIRST_RUN = "first.run";          // 0 after the first boot

    private Prefs() { }

    private static synchronized void load() {
        if (values != null) {
            return;
        }
        values = new Hashtable();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                if (data != null) {
                    DataInputStream in =
                        new DataInputStream(new ByteArrayInputStream(data));
                    int n = in.readInt();
                    for (int i = 0; i < n; i++) {
                        String k = in.readUTF();
                        String v = in.readUTF();
                        values.put(k, v);
                    }
                }
            }
        } catch (Exception e) {
            // corrupt or missing store: start with defaults
        } finally {
            close(rs);
        }
    }

    private static synchronized void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(values.size());
            for (Enumeration e = values.keys(); e.hasMoreElements();) {
                String k = (String) e.nextElement();
                out.writeUTF(k);
                out.writeUTF((String) values.get(k));
            }
            byte[] data = bos.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(data, 0, data.length);
            } else {
                rs.setRecord(1, data, 0, data.length);
            }
        } catch (Exception e) {
            // nothing sensible to do: settings just do not persist
        } finally {
            close(rs);
        }
    }

    static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    static String get(String key, String def) {
        load();
        String v = (String) values.get(key);
        return (v == null) ? def : v;
    }

    static boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        return v.equals("1") || v.equals("true");
    }

    static int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static void set(String key, String value) {
        load();
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        save();
    }

    static void setBool(String key, boolean value) {
        set(key, value ? "1" : "0");
    }

    /** Settings > Restore factory settings. */
    static void reset() {
        load();
        values.clear();
        save();
    }
}
