/*
 * S100 shell - small window onto the phone: battery level from sysfs,
 * memory, platform strings, and the tz.txt file j2me.sh reads at start.
 *
 * Files are read through the internal storage stream (the AMS runs in
 * the trusted internal suite, so no JSR-75 is needed). Every accessor
 * fails soft: on the PC emulator none of the sysfs nodes exist.
 */

package com.sun.midp.appmanager;

import java.io.IOException;
import javax.microedition.io.Connector;
import com.sun.midp.io.j2me.storage.RandomAccessStream;

final class SysInfo {
    private static final String[] BATTERY_NODES = {
        "/sys/class/power_supply/battery/capacity",
        "/sys/class/power_supply/bms/capacity",
    };
    private static final String[] CHARGING_NODES = {
        "/sys/class/power_supply/battery/status",
        "/sys/class/power_supply/usb/online",
    };

    private SysInfo() { }

    /** Reads a small text file; null if it cannot be opened. */
    static String readFile(String path, int max) {
        RandomAccessStream s = new RandomAccessStream();
        try {
            s.connect(path, Connector.READ);
            byte[] buf = new byte[max];
            int n = s.readBytes(buf, 0, max);
            if (n <= 0) {
                return null;
            }
            return new String(buf, 0, n).trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                s.disconnect();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    /** Writes (truncates) a small text file; false on failure. */
    static boolean writeFile(String path, String text) {
        RandomAccessStream s = new RandomAccessStream();
        try {
            s.connect(path, RandomAccessStream.READ_WRITE_TRUNCATE);
            byte[] b = text.getBytes();
            s.writeBytes(b, 0, b.length);
            s.commitWrite();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.disconnect();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    /** Battery percentage 0..100, or -1 when unknown. */
    static int batteryPercent() {
        for (int i = 0; i < BATTERY_NODES.length; i++) {
            String v = readFile(BATTERY_NODES[i], 16);
            if (v != null) {
                try {
                    int p = Integer.parseInt(v);
                    return Math.max(0, Math.min(100, p));
                } catch (NumberFormatException e) {
                    // try the next node
                }
            }
        }
        return -1;
    }

    static boolean charging() {
        String v = readFile(CHARGING_NODES[0], 16);
        if (v != null) {
            return v.equals("Charging") || v.equals("Full");
        }
        v = readFile(CHARGING_NODES[1], 4);
        return v != null && v.equals("1");
    }

    /** Directory the shell may write to: the internal suite storage (appdb/). */
    static String storageDir() {
        return com.sun.midp.io.j2me.storage.File.getStorageRoot(
            com.sun.midp.configurator.Constants.INTERNAL_STORAGE_ID);
    }

    static String platform() {
        String p = System.getProperty("microedition.platform");
        return (p == null) ? "phoneME" : p;
    }

    static String profiles() {
        String p = System.getProperty("microedition.profiles");
        String c = System.getProperty("microedition.configuration");
        return ((c == null) ? "CLDC" : c) + " / " + ((p == null) ? "MIDP" : p);
    }

    static long freeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    static long totalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    static String kb(long bytes) {
        return Long.toString(bytes / 1024) + " kB";
    }

    /** Kernel/hardware line from /proc, if readable. */
    static String hardware() {
        String cpu = readFile("/proc/cpuinfo", 2048);
        if (cpu == null) {
            return "unknown";
        }
        int i = cpu.indexOf("Hardware");
        if (i >= 0) {
            int c = cpu.indexOf(':', i);
            int e = cpu.indexOf('\n', i);
            if (c > 0) {
                return cpu.substring(c + 1, e < 0 ? cpu.length() : e).trim();
            }
        }
        return "ARM";
    }
}
