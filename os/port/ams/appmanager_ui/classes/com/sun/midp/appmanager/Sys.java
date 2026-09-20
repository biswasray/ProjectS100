/*
 * S100 shell - the phone below the Java runtime.
 *
 * Thin Java side of native/s100_native.c: directory listing and file
 * operations on absolute paths, running phone commands (synchronously
 * for quick ones, as polled background jobs for slow ones), the camera
 * frame converters and the video recorder. Everything is static; a
 * failure never throws, it returns null/false/-1 so the screens can show
 * a note instead.
 *
 * Paths: /data/j2me (J2ME_HOME) holds the runtime and its helper scripts
 * (s100_net.sh, s100_cam.sh); the phone's user storage is the fuse mount
 * /storage/emulated/0 and a memory card appears under /storage or
 * /mnt/media_rw when vold mounts it.
 */

package com.sun.midp.appmanager;

import java.util.Calendar;
import java.util.Date;
import java.util.TimerTask;
import java.util.Vector;

final class Sys {
    /** Where j2me.sh installed us. */
    static final String HOME = "/data/j2me";
    static final String TMP = HOME + "/tmp";
    static final String NET_SH = "sh " + HOME + "/s100_net.sh";
    static final String CAM_SH = "sh " + HOME + "/s100_cam.sh";
    static final String MEDIA_SH = "sh " + HOME + "/s100_media.sh";
    static final String LOC_SH = "sh " + HOME + "/s100_loc.sh";

    private static final String[] PHONE_ROOTS = {
        "/storage/emulated/0", "/data/media/0", "/sdcard", "/storage/emulated/legacy"
    };

    private Sys() { }

    /* ------------------------------------------------------------------ */
    /*                              natives                               */
    /* ------------------------------------------------------------------ */

    private static native String nList(String path);
    private static native int nType(String path);
    private static native long nSize(String path);
    private static native long nMtime(String path);
    private static native boolean nMkdir(String path);
    private static native boolean nRemove(String path);
    private static native boolean nRename(String from, String to);
    private static native boolean nCopy(String from, String to);
    private static native long nSpace(String path, boolean total);
    private static native String nExec(String cmd, int timeoutMs);
    private static native int nExitCode();
    private static native int nSpawn(String cmd);
    private static native boolean nAlive(int pid);
    private static native boolean nKill(int pid, boolean group);
    private static native int nYuvFrame(String path, int w, int h, int fmt, int rot,
                                        boolean mirror, int[] out, int ow, int oh);
    private static native int nYuvJpeg(String yuv, int w, int h, int fmt, int rot,
                                       boolean mirror, int div, int quality, String jpg);
    private static native int nRecStart(String yuv, int w, int h, int fmt, int rot,
                                        boolean mirror, String avi, int fps, int div,
                                        int quality, int audioRate);
    private static native int nRecStop();
    private static native int nRecFrames();
    private static native int nJpegDecode(String path, int maxW, int maxH, int[] out);
    /* music / video player (native/s100_media.c) */
    private static native int nMediaOpen(String path, int maxW, int maxH);
    private static native int nMediaInfo(int what);
    private static native String nMediaTag(int which);
    private static native int nMediaPlay();
    private static native void nMediaPause();
    private static native void nMediaStop();
    private static native void nMediaClose();
    private static native void nMediaSeek(int ms);
    private static native int nMediaPos();
    private static native int nMediaState();
    private static native void nMediaVolume(int pct);
    private static native int nMediaFrame(int[] out);

    /* ------------------------------------------------------------------ */
    /*                              files                                 */
    /* ------------------------------------------------------------------ */

    static final int NONE = 0, FILE = 1, DIR = 2, OTHER = 3;

    /** One directory entry. */
    static class Entry {
        String name;
        String path;
        boolean dir;
        long size;
        long mtime;      // seconds since the epoch

        String ext() {
            return extOf(name);
        }
    }

    static int type(String path) {
        try {
            return nType(path);
        } catch (Throwable t) {
            return NONE;
        }
    }

    static boolean exists(String path) {
        return type(path) != NONE;
    }

    static boolean isDir(String path) {
        return type(path) == DIR;
    }

    static long size(String path) {
        try {
            return nSize(path);
        } catch (Throwable t) {
            return -1;
        }
    }

    static long mtime(String path) {
        try {
            return nMtime(path);
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean mkdir(String path) {
        try {
            return nMkdir(path);
        } catch (Throwable t) {
            return false;
        }
    }

    /** mkdir -p */
    static boolean mkdirs(String path) {
        if (isDir(path)) {
            return true;
        }
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            mkdirs(path.substring(0, slash));
        }
        return mkdir(path);
    }

    /** Removes a file or an empty directory. */
    static boolean remove(String path) {
        try {
            return nRemove(path);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Removes a directory and everything below it. */
    static boolean removeTree(String path) {
        if (isDir(path)) {
            Vector v = list(path);
            for (int i = 0; v != null && i < v.size(); i++) {
                removeTree(((Entry) v.elementAt(i)).path);
            }
        }
        return remove(path);
    }

    static boolean rename(String from, String to) {
        try {
            return nRename(from, to);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean copy(String from, String to) {
        try {
            return nCopy(from, to);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copies a file or a whole directory. */
    static boolean copyTree(String from, String to) {
        if (!isDir(from)) {
            return copy(from, to);
        }
        if (!mkdirs(to)) {
            return false;
        }
        Vector v = list(from);
        for (int i = 0; v != null && i < v.size(); i++) {
            Entry e = (Entry) v.elementAt(i);
            if (!copyTree(e.path, to + "/" + e.name)) {
                return false;
            }
        }
        return true;
    }

    /** Free bytes on the file system holding path, or -1. */
    static long free(String path) {
        try {
            return nSpace(path, false);
        } catch (Throwable t) {
            return -1;
        }
    }

    static long total(String path) {
        try {
            return nSpace(path, true);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Entries of a directory, folders first, both sorted by name; null if unreadable. */
    static Vector list(String path) {
        String text;
        try {
            text = nList(path);
        } catch (Throwable t) {
            text = null;
        }
        if (text == null) {
            return null;
        }
        Vector dirs = new Vector();
        Vector files = new Vector();
        int pos = 0;
        int len = text.length();
        while (pos < len) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                nl = len;
            }
            String line = text.substring(pos, nl);
            pos = nl + 1;
            // t \t size \t mtime \t name
            int t1 = line.indexOf('\t');
            int t2 = line.indexOf('\t', t1 + 1);
            int t3 = line.indexOf('\t', t2 + 1);
            if (t1 < 0 || t2 < 0 || t3 < 0) {
                continue;
            }
            Entry e = new Entry();
            e.dir = line.charAt(0) == 'd';
            e.size = parseLong(line.substring(t1 + 1, t2));
            e.mtime = parseLong(line.substring(t2 + 1, t3));
            e.name = line.substring(t3 + 1);
            e.path = join(path, e.name);
            insertSorted(e.dir ? dirs : files, e);
        }
        for (int i = 0; i < files.size(); i++) {
            dirs.addElement(files.elementAt(i));
        }
        return dirs;
    }

    private static void insertSorted(Vector v, Entry e) {
        String k = e.name.toLowerCase();
        int lo = 0, hi = v.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (((Entry) v.elementAt(mid)).name.toLowerCase().compareTo(k) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        v.insertElementAt(e, lo);
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String join(String dir, String name) {
        if (dir.endsWith("/")) {
            return dir + name;
        }
        return dir + "/" + name;
    }

    static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return (i < 0) ? path : path.substring(i + 1);
    }

    static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        if (i <= 0) {
            return "/";
        }
        return path.substring(0, i);
    }

    /** Lower-case extension without the dot, "" if none. */
    static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot <= 0) ? name : name.substring(0, dot);
    }

    /** "12 kB", "3.4 MB" */
    static String sizeText(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " kB";
        }
        long tenths = bytes * 10 / (1024 * 1024);
        if (tenths < 1000) {
            return (tenths / 10) + "." + (tenths % 10) + " MB";
        }
        tenths = bytes * 10 / (1024L * 1024 * 1024);
        return (tenths / 10) + "." + (tenths % 10) + " GB";
    }

    /** "20.09.2026 17:52" for a Unix time in seconds. */
    static String dateText(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(seconds * 1000L));
        return Theme.two(c.get(Calendar.DAY_OF_MONTH)) + "."
            + Theme.two(c.get(Calendar.MONTH) + 1) + "."
            + c.get(Calendar.YEAR) + " "
            + Theme.two(c.get(Calendar.HOUR_OF_DAY)) + ":"
            + Theme.two(c.get(Calendar.MINUTE));
    }

    /** yyyymmdd_hhmmss for file names. */
    static String stamp() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) + Theme.two(c.get(Calendar.MONTH) + 1)
            + Theme.two(c.get(Calendar.DAY_OF_MONTH)) + "_"
            + Theme.two(c.get(Calendar.HOUR_OF_DAY)) + Theme.two(c.get(Calendar.MINUTE))
            + Theme.two(c.get(Calendar.SECOND));
    }

    /* ------------------------------------------------------------------ */
    /*                           storage roots                            */
    /* ------------------------------------------------------------------ */

    /** The phone's user memory (the KaiOS "internal storage"); null on the PC. */
    static String phoneRoot() {
        for (int i = 0; i < PHONE_ROOTS.length; i++) {
            if (isDir(PHONE_ROOTS[i])) {
                return PHONE_ROOTS[i];
            }
        }
        return null;
    }

    /**
     * The memory card mount point, or null when no card is inserted.
     * vold mounts cards under /storage/<label> (fuse) with the raw mount
     * in /mnt/media_rw/<label>; /proc/mounts is the reliable source.
     */
    static String cardRoot() {
        String mounts = SysInfo.readFile("/proc/mounts", 16384);
        String best = null;
        if (mounts != null) {
            int pos = 0;
            while (pos < mounts.length()) {
                int nl = mounts.indexOf('\n', pos);
                if (nl < 0) {
                    nl = mounts.length();
                }
                String line = mounts.substring(pos, nl);
                pos = nl + 1;
                int s1 = line.indexOf(' ');
                int s2 = line.indexOf(' ', s1 + 1);
                if (s1 < 0 || s2 < 0) {
                    continue;
                }
                String mp = line.substring(s1 + 1, s2);
                if (mp.startsWith("/storage/") && !mp.startsWith("/storage/emulated")
                        && !mp.equals("/storage/self") && !mp.startsWith("/storage/self/")) {
                    return mp;                      // fuse view, preferred
                }
                if (mp.startsWith("/mnt/media_rw/") && best == null) {
                    best = mp;
                }
            }
        }
        return best;
    }

    /* ------------------------------------------------------------------ */
    /*                              commands                              */
    /* ------------------------------------------------------------------ */

    /** Shell-quotes one argument. */
    static String q(String s) {
        StringBuffer b = new StringBuffer("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                b.append("'\\''");
            } else {
                b.append(c);
            }
        }
        b.append('\'');
        return b.toString();
    }

    private static int lastExit = -1;

    /**
     * Runs a command and returns its output (stdout+stderr), or null when
     * it could not be started. Blocks the whole VM: keep timeouts short.
     */
    static String exec(String cmd, int timeoutMs) {
        try {
            String out = nExec(cmd, timeoutMs);
            lastExit = nExitCode();
            return out;
        } catch (Throwable t) {
            lastExit = -1;
            return null;
        }
    }

    static int exitCode() {
        return lastExit;
    }

    static int spawn(String cmd) {
        try {
            return nSpawn(cmd);
        } catch (Throwable t) {
            return -1;
        }
    }

    static boolean alive(int pid) {
        try {
            return nAlive(pid);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean kill(int pid) {
        try {
            return nKill(pid, true);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Result of a background job. */
    interface JobListener {
        void onDone(String output, boolean ok);
    }

    private static int jobSeq;

    /**
     * Runs a slow command in the background and reports its output on the
     * event thread when it exits (or after timeoutMs, killed). The UI stays
     * responsive meanwhile; callers usually show Popup.wait() first.
     */
    static void run(final Shell shell, String cmd, final int timeoutMs,
                    final JobListener l) {
        mkdirs(TMP);
        final String out = TMP + "/job" + (++jobSeq) + ".out";
        remove(out);
        // the exit status lands in the file's last line so we can read it back
        final int pid = spawn("(" + cmd + ") > " + q(out) + " 2>&1; echo \"exit=$?\" >> " + q(out));
        if (pid <= 0) {
            l.onDone(null, false);
            return;
        }
        final long start = System.currentTimeMillis();
        final TimerTask[] task = new TimerTask[1];
        task[0] = shell.every(new Runnable() {
            public void run() {
                boolean running = alive(pid);
                boolean timedOut = System.currentTimeMillis() - start > timeoutMs;
                if (running && !timedOut) {
                    return;
                }
                task[0].cancel();
                if (running) {
                    kill(pid);
                }
                String text = SysInfo.readFile(out, 65536);
                remove(out);
                boolean ok = false;
                if (text != null) {
                    int i = -1, j;
                    while ((j = text.indexOf("exit=", i + 1)) >= 0) {
                        i = j;                       // the last one is ours
                    }
                    if (i >= 0) {
                        ok = text.substring(i + 5).trim().equals("0");
                        text = text.substring(0, i).trim();
                    }
                }
                l.onDone(text, ok && !timedOut);
            }
        }, 250);
    }

    /** Value of "key=..." in a script's output, or def. */
    static String field(String output, String key, String def) {
        if (output == null) {
            return def;
        }
        int pos = 0;
        while (pos <= output.length()) {
            int nl = output.indexOf('\n', pos);
            if (nl < 0) {
                nl = output.length();
            }
            String line = output.substring(pos, nl).trim();
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
            pos = nl + 1;
        }
        return def;
    }

    /** Lines of a script's output. */
    static Vector lines(String output) {
        Vector v = new Vector();
        if (output == null) {
            return v;
        }
        int pos = 0;
        while (pos < output.length()) {
            int nl = output.indexOf('\n', pos);
            if (nl < 0) {
                nl = output.length();
            }
            String line = output.substring(pos, nl).trim();
            if (line.length() > 0) {
                v.addElement(line);
            }
            pos = nl + 1;
        }
        return v;
    }

    /** Splits on a separator char (no String.split in CLDC). */
    static String[] split(String s, char sep) {
        Vector v = new Vector();
        int pos = 0;
        while (true) {
            int i = s.indexOf(sep, pos);
            if (i < 0) {
                v.addElement(s.substring(pos));
                break;
            }
            v.addElement(s.substring(pos, i));
            pos = i + 1;
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*                              camera                                */
    /* ------------------------------------------------------------------ */

    /** Chroma layouts of a frame dump: interleaved VU / UV, planar V,U / U,V. */
    static final int NV21 = 0, NV12 = 1, YV12 = 2, I420 = 3;

    /** Converts a frame dump into ow x oh 0xRRGGBB pixels; 0 = ok, -2 = torn frame. */
    static int yuvFrame(String path, int w, int h, int fmt, int rot, boolean mirror,
                        int[] out, int ow, int oh) {
        try {
            return nYuvFrame(path, w, h, fmt, rot, mirror, out, ow, oh);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int yuvJpeg(String yuv, int w, int h, int fmt, int rot, boolean mirror,
                       int div, int quality, String jpg) {
        try {
            return nYuvJpeg(yuv, w, h, fmt, rot, mirror, div, quality, jpg);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int recStart(String yuv, int w, int h, int fmt, int rot, boolean mirror,
                        String avi, int fps, int div, int quality, int audioRate) {
        try {
            return nRecStart(yuv, w, h, fmt, rot, mirror, avi, fps, div, quality, audioRate);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int recStop() {
        try {
            return nRecStop();
        } catch (Throwable t) {
            return -1;
        }
    }

    static int recFrames() {
        try {
            return nRecFrames();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Decodes a JPEG reduced (1/2, 1/4, 1/8) until it has at most maxW*maxH
     * pixels into out (maxW*maxH ints); returns (w << 16) | h or a negative
     * error.
     */
    static int jpegDecode(String path, int maxW, int maxH, int[] out) {
        try {
            return nJpegDecode(path, maxW, maxH, out);
        } catch (Throwable t) {
            return -1;
        }
    }

    /* ------------------------------------------------------------------ */
    /*                           music / video                            */
    /* ------------------------------------------------------------------ */

    /** nMediaInfo selectors. */
    static final int M_DURATION = 0, M_WIDTH = 1, M_HEIGHT = 2, M_HAS_AUDIO = 3,
        M_HAS_VIDEO = 4, M_RATE = 5, M_CHANNELS = 6, M_BITRATE = 7, M_TYPE = 8,
        M_FRAMES = 9, M_FPS100 = 10, M_DROPPED = 11;
    /** nMediaState values (negative = error, see mediaError()). */
    static final int MS_STOPPED = 0, MS_PLAYING = 1, MS_PAUSED = 2, MS_ENDED = 3;

    /**
     * Opens a file in the player: 0 ok, -1 unreadable, -2 unknown format,
     * -3 unsupported codec, -4 no video decoder built in.
     */
    static int mediaOpen(String path, int maxW, int maxH) {
        try {
            return nMediaOpen(path, maxW, maxH);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int mediaInfo(int what) {
        try {
            return nMediaInfo(what);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** ID3 title (0), artist (1) or album (2); null if the file has none. */
    static String mediaTag(int which) {
        try {
            return nMediaTag(which);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean mediaPlay() {
        try {
            return nMediaPlay() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static void mediaPause() {
        try {
            nMediaPause();
        } catch (Throwable t) {
            // ignore
        }
    }

    static void mediaStop() {
        try {
            nMediaStop();
        } catch (Throwable t) {
            // ignore
        }
    }

    static void mediaClose() {
        try {
            nMediaClose();
        } catch (Throwable t) {
            // ignore
        }
    }

    static void mediaSeek(int ms) {
        try {
            nMediaSeek(ms);
        } catch (Throwable t) {
            // ignore
        }
    }

    static int mediaPos() {
        try {
            return nMediaPos();
        } catch (Throwable t) {
            return 0;
        }
    }

    static int mediaState() {
        try {
            return nMediaState();
        } catch (Throwable t) {
            return -2;
        }
    }

    /** Text for a negative mediaState(). */
    static String mediaError(int state) {
        switch (-state - 1) {
        case 1: return "Cannot open the sound device";
        case 2: return "Unsupported sound format";
        case 3: return "Cannot read the file";
        default: return "Playback failed";
        }
    }

    static void mediaVolume(int pct) {
        try {
            nMediaVolume(pct);
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Copies the latest video frame into out; (w << 16) | h, 0 when unchanged. */
    static int mediaFrame(int[] out) {
        try {
            return nMediaFrame(out);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** "3:07" / "1:02:15" for a duration in ms. */
    static String clock(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int s = ms / 1000;
        int m = s / 60;
        s %= 60;
        if (m >= 60) {
            return (m / 60) + ":" + Theme.two(m % 60) + ":" + Theme.two(s);
        }
        return m + ":" + Theme.two(s);
    }
}
